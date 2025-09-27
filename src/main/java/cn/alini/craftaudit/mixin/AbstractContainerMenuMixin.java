package cn.alini.craftaudit.mixin;

import cn.alini.craftaudit.event.container.ContainerSessionTracker;
import cn.alini.craftaudit.storage.Database;
import cn.alini.craftaudit.storage.LogEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;

@Mixin(AbstractContainerMenu.class)
public class AbstractContainerMenuMixin {

    // 不再在 Mixin 里维护 open 快照/坐标，全部交给 ContainerSessionTracker

    @Inject(method = "removed", at = @At("HEAD"))
    private void onRemoved(Player player, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        AbstractContainerMenu menu = (AbstractContainerMenu)(Object)this;

        // 取回该菜单的会话（与 Open 时绑定的那个），失败则不记录
        ContainerSessionTracker.Session session = ContainerSessionTracker.takeSession(menu);
        if (session == null) return;

        // 生成关闭时快照并计算差异
        List<ItemStack> after = ContainerSessionTracker.snapshotContainerSlots(menu);
        Map<String, Integer> diff = ContainerSessionTracker.compare(session.openSnapshot, after);
        if (diff.isEmpty()) return;

        BlockPos bp = session.pos;
        String dim = session.dimension;
        String target = session.menuClassName; // menu.getClass().getName() 也可以

        for (Map.Entry<String, Integer> e : diff.entrySet()) {
            String itemId = e.getKey();
            int delta = e.getValue();
            if (delta == 0) continue;
            String action = delta > 0 ? "put" : "take";
            int absCount = Math.abs(delta);

            Database.get().insertAsync(new LogEntry(
                    System.currentTimeMillis(),
                    dim,
                    bp.getX(), bp.getY(), bp.getZ(),
                    serverPlayer.getName().getString(),
                    action,
                    target,
                    String.format("{\"item\":\"%s\",\"count\":%d}", itemId, absCount)
            ));
        }
    }
}