package cn.alini.craftaudit.mixin;

import cn.alini.craftaudit.event.container.ContainerPosTracker;
import cn.alini.craftaudit.storage.Database;
import cn.alini.craftaudit.storage.LogEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.*;

@Mixin(AbstractContainerMenu.class)
public class AbstractContainerMenuMixin {
    private List<ItemStack> craftaudit_openSnapshot = null;

    @Inject(method = "broadcastChanges", at = @At("HEAD"))
    private void onBroadcastChanges(CallbackInfo ci) {
        AbstractContainerMenu menu = (AbstractContainerMenu)(Object)this;
        if (this.craftaudit_openSnapshot == null) {
            this.craftaudit_openSnapshot = snapshotContainerSlots(menu);
        }
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void onRemoved(Player player, CallbackInfo ci) {
        AbstractContainerMenu menu = (AbstractContainerMenu)(Object)this;
        if (!(player instanceof ServerPlayer serverPlayer)) return;

        List<ItemStack> before = this.craftaudit_openSnapshot;
        List<ItemStack> after = snapshotContainerSlots(menu);

        Map<String, Integer> diff = compare(before, after);

        if (diff != null && !diff.isEmpty()) {
            // 直接从 Tracker 取坐标（不再从菜单里扒）
            ContainerPosTracker.PosAndDim pad = ContainerPosTracker.takeActive(serverPlayer.getUUID(), serverPlayer);
            BlockPos bp = pad.pos();
            String dimension = pad.dimension();
            String target = menu.getClass().getName();

            for (Map.Entry<String, Integer> e : diff.entrySet()) {
                String itemId = e.getKey();
                int count = e.getValue();
                if (count == 0) continue;
                String action = count > 0 ? "put" : "take";
                int absCount = Math.abs(count);

                Database.get().insertAsync(new LogEntry(
                        System.currentTimeMillis(),
                        dimension,
                        bp.getX(), bp.getY(), bp.getZ(),
                        serverPlayer.getName().getString(),
                        action,
                        target,
                        String.format("{\"item\":\"%s\",\"count\":%d}", itemId, absCount)
                ));
            }
        }

        this.craftaudit_openSnapshot = null;
    }

    /**
     * 只排除玩家背包/饰品/末影箱等，快照容器槽
     */
    private List<ItemStack> snapshotContainerSlots(AbstractContainerMenu menu) {
        List<ItemStack> items = new ArrayList<>();
        for (Slot slot : menu.slots) {
            // 跳过玩家背包槽
            if (slot.container instanceof net.minecraft.world.entity.player.Inventory) continue;
            // 跳过常见饰品与末影箱等
            String name = slot.container.getClass().getName().toLowerCase();
            if (name.contains("curio") || name.contains("trinket") || name.contains("bauble")) continue;
            if (name.contains("enderchest")) continue;

            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) items.add(stack.copy());
        }
        return items;
    }

    private Map<String, Integer> compare(List<ItemStack> before, List<ItemStack> after) {
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
        // 清理 0 值
        map.entrySet().removeIf(e -> e.getValue() == 0);
        return map;
    }
}