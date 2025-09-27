package cn.alini.craftaudit.rollback;

import cn.alini.craftaudit.storage.Database;
import cn.alini.craftaudit.storage.LogEntry;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 回滚/恢复实现：
 * - restoreBreaks: 恢复“破坏类”记录（break / natural_break），可选按环境 cause 过滤。
 * - restoreKills: 恢复“击杀”记录（kill），对非玩家受害者在日志坐标生成同类型新实体。
 * - rollback/restore: 兼容旧方法（用于“回档某人的方块操作”）。
 */
public final class RollbackService {
    private static final Logger LOGGER = LogUtils.getLogger();

    private RollbackService() {}

    // ========= 新增：恢复“破坏类”记录 =========
    public static int restoreBreaks(ServerLevel level,
                                    String dimension,
                                    BlockPos center,
                                    int radius,
                                    long sinceMs,
                                    List<String> actions,              // e.g. List.of("break","natural_break")
                                    String naturalCauseFilterOrNull,   // explosion | fluid | gravity | null
                                    UUID executorId) {
        List<LogEntry> logs = Database.get().queryLogsRegionSince(
                dimension,
                center.getX() - radius, center.getX() + radius,
                center.getY() - radius, center.getY() + radius,
                center.getZ() - radius, center.getZ() + radius,
                sinceMs,
                actions,
                null
        );

        logs.sort(Comparator.comparingLong(LogEntry::timeMillis).reversed());

        UndoManager.UndoBundle undo = (executorId != null) ? new UndoManager.UndoBundle(dimension) : null;

        int changed = 0;
        for (LogEntry e : logs) {
            String action = e.action();
            if (naturalCauseFilterOrNull != null) {
                if (!"natural_break".equals(action)) continue;
                String cause = extractJsonStringField(e.data(), "cause");
                if (!naturalCauseFilterOrNull.equalsIgnoreCase(cause)) continue;
            }
            if (!"break".equals(action) && !"natural_break".equals(action)) continue;

            BlockPos pos = new BlockPos(e.x(), e.y(), e.z());
            if (!level.isLoaded(pos)) continue;

            try {
                // 改动前快照
                if (undo != null) {
                    BlockState before = level.getBlockState(pos);
                    CompoundTag beTag = null;
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be != null) {
                        try {
                            beTag = be.saveWithFullMetadata();
                            beTag.putInt("x", pos.getX());
                            beTag.putInt("y", pos.getY());
                            beTag.putInt("z", pos.getZ());
                        } catch (Exception ignored) {}
                    }
                    undo.entries.add(new UndoManager.UndoEntry(pos, before, beTag));
                }

                // 放回方块默认态
                Block block = resolveBlock(e.target());
                if (block == null) continue;
                boolean ok = level.setBlockAndUpdate(pos, block.defaultBlockState());

                // 玩家 break 尝试回填 NBT
                if ("break".equals(action)) {
                    String nbtB64 = extractJsonStringField(e.data(), "nbt");
                    if (nbtB64 != null && !nbtB64.isEmpty()) {
                        try {
                            byte[] bytes = Base64.getDecoder().decode(nbtB64);
                            CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(bytes));
                            tag.putInt("x", pos.getX());
                            tag.putInt("y", pos.getY());
                            tag.putInt("z", pos.getZ());
                            BlockEntity be2 = level.getBlockEntity(pos);
                            if (be2 != null) {
                                be2.load(tag);
                                be2.setChanged();
                                ok = true;
                            }
                        } catch (Exception ex) {
                            LOGGER.warn("[craftaudit] 恢复 NBT 失败 @ {}: {}", pos, ex.toString());
                        }
                    }
                }
                if (ok) changed++;
            } catch (Exception ex) {
                LOGGER.warn("[craftaudit] 恢复破坏失败 @ {} {} -> {}: {}", e.x()+"/"+e.y()+"/"+e.z(), e.action(), e.target(), ex.toString());
            }
        }

        if (executorId != null && undo != null && !undo.entries.isEmpty()) {
            UndoManager.saveLast(executorId, undo);
        }
        return changed;
    }

    // ========= 新增：恢复“击杀”记录 =========
    public static int restoreKills(ServerLevel level,
                                   String dimension,
                                   BlockPos center,
                                   int radius,
                                   long sinceMs,
                                   String entityFilterOrNull, // 例：null 或 minecraft:villager
                                   UUID executorId) {
        List<LogEntry> logs = Database.get().queryLogsRegionSince(
                dimension,
                center.getX() - radius, center.getX() + radius,
                center.getY() - radius, center.getY() + radius,
                center.getZ() - radius, center.getZ() + radius,
                sinceMs,
                List.of("kill"),
                null
        );

        logs.sort(Comparator.comparingLong(LogEntry::timeMillis).reversed());

        UndoManager.UndoBundle undo = (executorId != null) ? new UndoManager.UndoBundle(dimension) : null;

        int spawned = 0;
        for (LogEntry e : logs) {
            String target = e.target(); // 可能是 player:Name 或 实体ID
            if (target == null) continue;

            // 过滤玩家：不尝试“复活玩家”
            if (target.startsWith("player:")) continue;

            // 可选实体过滤
            if (entityFilterOrNull != null && !entityFilterOrNull.equalsIgnoreCase(target)) continue;

            BlockPos pos = new BlockPos(e.x(), e.y(), e.z());
            if (!level.isLoaded(pos)) continue;

            try {
                EntityType<?> type = resolveEntityType(target);
                if (type == null) continue;

                Entity ent = type.create(level);
                if (ent == null) continue;

                // 生成在方块中心
                ent.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, level.random.nextFloat() * 360f, 0f);
                boolean ok = level.addFreshEntity(ent);
                if (ok) {
                    spawned++;
                    if (undo != null) {
                        undo.spawnedEntities.add(ent.getUUID());
                    }
                }
            } catch (Exception ex) {
                LOGGER.warn("[craftaudit] 恢复击杀失败 @ {} -> {}: {}", e.x()+"/"+e.y()+"/"+e.z(), target, ex.toString());
            }
        }

        if (executorId != null && undo != null && (!undo.spawnedEntities.isEmpty() || !undo.entries.isEmpty())) {
            UndoManager.saveLast(executorId, undo);
        }
        return spawned;
    }

    // ========= 兼容旧方法（方块回滚/恢复） =========

    public static int restore(ServerLevel level, String dimension, BlockPos center, int radius, long sinceMs) {
        return rollback(level, dimension, center, radius, sinceMs, null);
    }

    public static int restore(ServerLevel level, String dimension, BlockPos center, int radius, long sinceMs, UUID executorId) {
        return rollback(level, dimension, center, radius, sinceMs, null, executorId);
    }

    public static int rollback(ServerLevel level, String dimension, BlockPos center, int radius, long sinceMs, String playerFilterOrNull) {
        return rollback(level, dimension, center, radius, sinceMs, playerFilterOrNull, null);
    }

    public static int rollback(ServerLevel level, String dimension, BlockPos center, int radius, long sinceMs, String playerFilterOrNull, UUID executorId) {
        List<LogEntry> logs = Database.get().queryLogsRegionSince(
                dimension,
                center.getX() - radius, center.getX() + radius,
                center.getY() - radius, center.getY() + radius,
                center.getZ() - radius, center.getZ() + radius,
                sinceMs,
                List.of("break", "place"),
                playerFilterOrNull
        );

        logs.sort(Comparator.comparingLong(LogEntry::timeMillis).reversed());

        UndoManager.UndoBundle undo = (executorId != null) ? new UndoManager.UndoBundle(dimension) : null;

        int changed = 0;
        for (LogEntry e : logs) {
            BlockPos pos = new BlockPos(e.x(), e.y(), e.z());
            if (!level.isLoaded(pos)) continue;

            try {
                if (undo != null) {
                    BlockState before = level.getBlockState(pos);
                    CompoundTag beTag = null;
                    BlockEntity be = level.getBlockEntity(pos);
                    if (be != null) {
                        try {
                            beTag = be.saveWithFullMetadata();
                            beTag.putInt("x", pos.getX());
                            beTag.putInt("y", pos.getY());
                            beTag.putInt("z", pos.getZ());
                        } catch (Exception ignored) {}
                    }
                    undo.entries.add(new UndoManager.UndoEntry(pos, before, beTag));
                }

                if (Objects.equals(e.action(), "place")) {
                    if (level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState())) changed++;
                } else if (Objects.equals(e.action(), "break")) {
                    Block block = resolveBlock(e.target());
                    if (block == null) continue;
                    boolean ok = level.setBlockAndUpdate(pos, block.defaultBlockState());

                    String nbtB64 = extractJsonStringField(e.data(), "nbt");
                    if (nbtB64 != null && !nbtB64.isEmpty()) {
                        try {
                            byte[] bytes = Base64.getDecoder().decode(nbtB64);
                            CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(bytes));
                            tag.putInt("x", pos.getX());
                            tag.putInt("y", pos.getY());
                            tag.putInt("z", pos.getZ());
                            BlockEntity be2 = level.getBlockEntity(pos);
                            if (be2 != null) {
                                be2.load(tag);
                                be2.setChanged();
                                ok = true;
                            }
                        } catch (Exception ex) {
                            LOGGER.warn("[craftaudit] 回档 NBT 失败 @ {}: {}", pos, ex.toString());
                        }
                    }
                    if (ok) changed++;
                }
            } catch (Exception ex) {
                LOGGER.warn("[craftaudit] 回档失败 @ {} {} -> {}: {}", pos, e.action(), e.target(), ex.toString());
            }
        }

        if (executorId != null && undo != null && (!undo.entries.isEmpty() || !undo.spawnedEntities.isEmpty())) {
            UndoManager.saveLast(executorId, undo);
        }
        return changed;
    }

    private static Block resolveBlock(String id) {
        try {
            return net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id));
        } catch (Exception ignore) {
            return null;
        }
    }

    private static EntityType<?> resolveEntityType(String id) {
        try {
            return net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(id));
        } catch (Exception ignore) {
            return null;
        }
    }

    // 轻量 JSON 取字符串字段
    private static String extractJsonStringField(String json, String field) {
        if (json == null) return null;
        String key = "\"" + field + "\":\"";
        int i = json.indexOf(key);
        if (i < 0) return null;
        int start = i + key.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return null;
        return json.substring(start, end);
    }
}