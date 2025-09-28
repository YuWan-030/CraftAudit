package cn.alini.craftaudit.event.container;

import cn.alini.craftaudit.storage.Database;
import cn.alini.craftaudit.storage.LogEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跟踪玩家打开容器时的方块坐标，并与“菜单实例”建立一一会话绑定，保证高频开关时不串位。
 *
 * 流程：
 * 1) RightClickBlock: pendingByPlayer[uuid] = (pos, dim, time)
 * 2) PlayerContainerEvent.Open: sessionsByMenu[menu] = Session(pos, dim, openSnapshot, playerUuid)
 * 3) removed/Close: 通过菜单实例精确取回 Session，比对快照并写日志，然后清理
 */
@Mod.EventBusSubscriber
public final class ContainerSessionTracker {

    // RightClick 到 Open 的最大间隔，超过则认为不是同一次打开
    private static final long PENDING_TIMEOUT_MS = 2000;

    // 记录玩家刚右键的坐标（短时有效）
    private static final Map<UUID, Pending> pendingByPlayer = new ConcurrentHashMap<>();

    // 会话表：用菜单实例作为 key，避免同一玩家高频开关时串位；WeakHashMap 防止泄露
    private static final Map<AbstractContainerMenu, Session> sessionsByMenu =
            Collections.synchronizedMap(new WeakHashMap<>());


    // 右键方块：记录可能的容器方块坐标
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;

        BlockPos pos = event.getPos();
        String dim = player.level().dimension().location().toString();
        pendingByPlayer.put(player.getUUID(), new Pending(pos, dim, System.currentTimeMillis()));
        // 不取消事件
    }

    // 打开容器：把 pending 绑定到“本次菜单实例”，并做打开快照
    @SubscribeEvent
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;

        AbstractContainerMenu menu = event.getContainer();
        UUID uuid = player.getUUID();
        Pending pend = pendingByPlayer.remove(uuid);

        BlockPos pos;
        String dim;
        long now = System.currentTimeMillis();
        if (pend != null && now - pend.atMs <= PENDING_TIMEOUT_MS) {
            pos = pend.pos;
            dim = pend.dimension;
        } else {
            // 放弃：虚拟容器（插件箱子等）或玩家直接打开背包
            return;
            // 兜底：用玩家指向方块，否则脚下
//            BlockPos onPos = player.getOnPos();
//            pos = onPos;
//            dim = player.level().dimension().location().toString();
        }

        // 构建打开时快照（只快照目标容器槽）
        List<ItemStack> openSnapshot = snapshotContainerSlots(menu);

        Session session = new Session(uuid, pos, dim, openSnapshot, menu.getClass().getName());
        sessionsByMenu.put(menu, session);
    }

    /**
     * 被 Mixin 在 removed 里调用：
     * 取出并移除该菜单的会话；如果没有，返回 null。
     */
    public static Session takeSession(AbstractContainerMenu menu) {
        return sessionsByMenu.remove(menu);
    }

    /**
     * 供 Mixin/Close 使用：生成当前容器的“after”快照。
     * 注意：这里与 openSnapshot 的过滤策略要保持一致。
     */
    public static List<ItemStack> snapshotContainerSlots(AbstractContainerMenu menu) {
        String menuName = menu.getClass().getName();

        // 精确跳过 Curios
        if (menuName.equals("top.theillusivec4.curios.common.inventory.container.CuriosContainerV2")) {
            return Collections.emptyList();
        }

        List<ItemStack> items = new ArrayList<>();
        for (Slot slot : menu.slots) {
            // 跳过玩家背包
            if (slot.container instanceof net.minecraft.world.entity.player.Inventory) continue;

            // 其它虚拟容器
            String name = slot.container.getClass().getName().toLowerCase();
            if (name.contains("curio") || name.contains("trinket") || name.contains("bauble")) continue;
            if (name.contains("enderchest")) continue;
            if (name.contains("lightmanscurrency")) continue;

            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) items.add(stack.copy());
        }
        return items;
    }

    /**
     * 计算 before/after 的差异（key=物品ID，value=after-before）
     */
    public static Map<String, Integer> compare(List<ItemStack> before, List<ItemStack> after) {
        Map<String, Integer> map = new HashMap<>();
        if (before != null) {
            for (ItemStack stack : before) {
                String name = ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();
                map.put(name, map.getOrDefault(name, 0) - stack.getCount());
            }
        }
        if (after != null) {
            for (ItemStack stack : after) {
                String name = ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();
                map.put(name, map.getOrDefault(name, 0) + stack.getCount());
            }
        }
        map.entrySet().removeIf(e -> e.getValue() == 0);
        return map;
    }

    // ===== 数据结构 =====
    private record Pending(BlockPos pos, String dimension, long atMs) {}

    public static final class Session {
        public final UUID playerId;
        public final BlockPos pos;
        public final String dimension;
        public final List<ItemStack> openSnapshot;
        public final String menuClassName;

        Session(UUID playerId, BlockPos pos, String dimension, List<ItemStack> openSnapshot, String menuClassName) {
            this.playerId = playerId;
            this.pos = pos;
            this.dimension = dimension;
            this.openSnapshot = openSnapshot != null ? openSnapshot : Collections.emptyList();
            this.menuClassName = menuClassName;
        }
    }
}