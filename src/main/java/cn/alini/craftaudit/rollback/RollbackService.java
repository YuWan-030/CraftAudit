package cn.alini.craftaudit.rollback;

import cn.alini.craftaudit.storage.Database;
import cn.alini.craftaudit.storage.LogEntry;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
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
 * 回滚/恢复：对 break/place 进行反向应用。
 * 同时采集“改动前快照”，供撤销使用（UndoManager）。
 */
public final class RollbackService {
    private static final Logger LOGGER = LogUtils.getLogger();

    private RollbackService() {}

    // 老签名（兼容旧调用）
    public static int restore(ServerLevel level, String dimension, BlockPos center, int radius, long sinceMs) {
        return restore(level, dimension, center, radius, sinceMs, null);
    }
    public static int rollback(ServerLevel level, String dimension, BlockPos center, int radius, long sinceMs, String playerFilterOrNull) {
        return rollback(level, dimension, center, radius, sinceMs, playerFilterOrNull, null);
    }

    // 新签名：带执行者 UUID，用于保存撤销快照
    public static int restore(ServerLevel level, String dimension, BlockPos center, int radius, long sinceMs, UUID executorId) {
        return rollback(level, dimension, center, radius, sinceMs, null, executorId);
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
                // 采集“改动前快照”
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

                    // 若日志 data 含 NBT（break 时序列化的 BE NBT），尝试恢复
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

        if (executorId != null && undo != null && !undo.entries.isEmpty()) {
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

    // 轻量 JSON 取字符串字段（避免引第三方库）
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