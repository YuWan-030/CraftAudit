package cn.alini.craftaudit.mixin;

import cn.alini.craftaudit.AuditModeManager;
import cn.alini.craftaudit.storage.Database;
import cn.alini.craftaudit.storage.LogEntry;
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

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(CallbackInfo ci) {
        // Snapshot将在removed中动态获取，无需在init时获取
    }

    @Inject(method = "removed", at = @At("HEAD"))
    private void onRemoved(Player player, CallbackInfo ci) {
        AbstractContainerMenu menu = (AbstractContainerMenu)(Object)this;
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        if (AuditModeManager.isAuditing(serverPlayer.getUUID())) return;

        List<ItemStack> before;
        if (craftaudit_openSnapshot == null) {
            // 第一次关闭时快照
            before = snapshotContainerSlots(menu, serverPlayer);
            craftaudit_openSnapshot = before;
        } else {
            before = craftaudit_openSnapshot;
        }
        List<ItemStack> after = snapshotContainerSlots(menu, serverPlayer);

        Map<String, Integer> diff = compare(before, after);
        if (diff.isEmpty()) return;

        int x = (int)serverPlayer.getX(), y = (int)serverPlayer.getY()-1, z = (int)serverPlayer.getZ();
        String dimension = serverPlayer.level().dimension().location().toString();
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
                    x, y, z,
                    serverPlayer.getName().getString(),
                    action,
                    target,
                    String.format("{\"item\":\"%s\",\"count\":%d}", itemId, absCount)
            ));
        }
        craftaudit_openSnapshot = null;
    }

    /**
     * 只快照“非玩家背包槽”（即容器槽）。
     * 兼容绝大多数原版/模组容器，包括金属储物桶。
     */
    private List<ItemStack> snapshotContainerSlots(AbstractContainerMenu menu, Player player) {
        List<ItemStack> items = new ArrayList<>();
        for (Slot slot : menu.slots) {
            if (isPlayerInventorySlot(slot, player)) continue; // 跳过玩家背包槽
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) items.add(stack.copy());
        }
        return items;
    }

    /**
     * 判断某个Slot是否属于玩家背包
     * 绝大多数容器：slot.container == player.inventory
     * 可根据模组特殊情况扩展
     */
    private boolean isPlayerInventorySlot(Slot slot, Player player) {
        return slot.container == player.getInventory();
    }

    private Map<String, Integer> compare(List<ItemStack> before, List<ItemStack> after) {
        Map<String, Integer> map = new HashMap<>();
        for (ItemStack stack : before) {
            String name = ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();
            map.put(name, map.getOrDefault(name, 0) - stack.getCount());
        }
        for (ItemStack stack : after) {
            String name = ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();
            map.put(name, map.getOrDefault(name, 0) + stack.getCount());
        }
        return map;
    }
}