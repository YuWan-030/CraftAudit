package cn.alini.craftaudit.rollback;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理“上一次回滚/恢复”的撤销快照（每个玩家一份）。
 * 仅保留最近一次，新的回滚会覆盖旧快照。
 */
public final class UndoManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    private UndoManager() {}

    public static final class UndoEntry {
        public final BlockPos pos;
        public final BlockState beforeState;
        public final CompoundTag beforeBeNbt; // 可为空

        public UndoEntry(BlockPos pos, BlockState beforeState, CompoundTag beforeBeNbt) {
            this.pos = pos;
            this.beforeState = beforeState;
            this.beforeBeNbt = beforeBeNbt;
        }
    }

    public static final class UndoBundle {
        public final String dimensionId; // 例如 minecraft:overworld
        public final List<UndoEntry> entries = new ArrayList<>();

        public UndoBundle(String dimensionId) {
            this.dimensionId = dimensionId;
        }
    }

    // 每位玩家一份最近快照
    private static final Map<UUID, UndoBundle> LAST_BUNDLE = new ConcurrentHashMap<>();

    public static void saveLast(UUID playerId, UndoBundle bundle) {
        LAST_BUNDLE.put(playerId, bundle);
    }

    public static boolean hasUndo(UUID playerId) {
        return LAST_BUNDLE.containsKey(playerId);
    }

    /**
     * 应用撤销：将方块恢复到“改动前”状态，并回填方块实体 NBT。
     * 成功后清除快照。
     */
    public static int applyUndo(ServerPlayer executor) {
        UndoBundle bundle = LAST_BUNDLE.remove(executor.getUUID());
        if (bundle == null) return 0;

        ServerLevel level = resolveLevel(executor.getServer(), bundle.dimensionId);
        if (level == null) {
            LOGGER.warn("[craftaudit] 未找到维度: {}", bundle.dimensionId);
            return 0;
        }

        int changed = 0;
        for (UndoEntry e : bundle.entries) {
            try {
                boolean ok = level.setBlockAndUpdate(e.pos, e.beforeState);
                if (e.beforeBeNbt != null) {
                    BlockEntity be = level.getBlockEntity(e.pos);
                    if (be != null) {
                        // 确保坐标一致
                        e.beforeBeNbt.putInt("x", e.pos.getX());
                        e.beforeBeNbt.putInt("y", e.pos.getY());
                        e.beforeBeNbt.putInt("z", e.pos.getZ());
                        be.load(e.beforeBeNbt);
                        be.setChanged();
                        ok = true;
                    }
                }
                if (ok) changed++;
            } catch (Exception ex) {
                LOGGER.warn("[craftaudit] 撤销失败 @ {}: {}", e.pos, ex.toString());
            }
        }
        return changed;
    }

    /**
     * 跨版本安全的维度解析：遍历服务器所有已加载的维度，比对维度 ID 字符串。
     * 维度 ID 形如 minecraft:overworld、minecraft:the_nether 等。
     */
    private static ServerLevel resolveLevel(MinecraftServer server, String dimensionId) {
        try {
            for (ServerLevel lvl : server.getAllLevels()) {
                String id = lvl.dimension().location().toString();
                if (dimensionId.equals(id)) {
                    return lvl;
                }
            }
        } catch (Exception ignored) { }
        return null;
    }
}