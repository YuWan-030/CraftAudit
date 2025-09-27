package cn.alini.craftaudit.event.container;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 负责跟踪玩家打开容器时对应的方块坐标。
 * 流程：
 * - 右键方块时（RightClickBlock）：pending[uuid] = (pos, dim, time)
 * - 打开容器时（PlayerContainerEvent.Open）：active[uuid] = pending[uuid]，并清理 pending
 * - 关闭/removed 写日志时，从 active 取出并清理
 */
@Mod.EventBusSubscriber
public final class ContainerPosTracker {

    private static final long PENDING_TIMEOUT_MS = 1000; // 2秒内认为是同一次打开

    private static final Map<UUID, Pending> pendingByPlayer = new ConcurrentHashMap<>();
    private static final Map<UUID, PosAndDim> activeByPlayer = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;

        BlockPos pos = event.getPos();
        Level level = player.level();
        String dim = level.dimension().location().toString();

        pendingByPlayer.put(player.getUUID(), new Pending(pos, dim, System.currentTimeMillis()));
        // 不取消事件，让容器正常打开
    }

    @SubscribeEvent
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;

        UUID id = player.getUUID();
        Pending p = pendingByPlayer.remove(id);
        String dim = player.level().dimension().location().toString();

        if (p != null && System.currentTimeMillis() - p.atMs <= PENDING_TIMEOUT_MS) {
            // 使用刚刚右键的方块坐标
            activeByPlayer.put(id, new PosAndDim(p.pos, p.dimension));
        } else {
            // 保底：没有 pending 时，用玩家脚下（或 getOnPos）记录一次
            BlockPos fallback = player.getOnPos() != null ? player.getOnPos() : player.blockPosition();
            activeByPlayer.put(id, new PosAndDim(fallback, dim));
        }
    }

    /**
     * 在 removed/Close 时获取并清理本次容器坐标
     */
    public static PosAndDim takeActive(UUID playerId, ServerPlayer player) {
        PosAndDim pad = activeByPlayer.remove(playerId);
        if (pad != null) return pad;
        // 兜底
        String dim = player.level().dimension().location().toString();
        BlockPos fallback = player.getOnPos() != null ? player.getOnPos() : player.blockPosition();
        return new PosAndDim(fallback, dim);
    }

    private record Pending(BlockPos pos, String dimension, long atMs) {}
    public record PosAndDim(BlockPos pos, String dimension) {}
}