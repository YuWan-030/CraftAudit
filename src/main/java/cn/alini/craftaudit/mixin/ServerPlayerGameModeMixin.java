package cn.alini.craftaudit.mixin;

import cn.alini.craftaudit.AuditModeManager;
import cn.alini.craftaudit.storage.Database;
import cn.alini.craftaudit.storage.LogEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerGameMode.class)
public class ServerPlayerGameModeMixin {

    @Inject(method = "destroyBlock", at = @At("HEAD"))
    private void craftaudit$onBlockBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        ServerPlayer player = ((ServerPlayerGameModeAccessor)(Object)this).craftaudit$getPlayer();
        if (AuditModeManager.isAuditing(player.getUUID())) return;
        Level world = player.level();
        BlockState state = world.getBlockState(pos);
        Database db = Database.get();
        if (db != null) {
            db.insertAsync(new LogEntry(
                    System.currentTimeMillis(),
                    world.dimension().location().toString(),
                    pos.getX(), pos.getY(), pos.getZ(),
                    player.getName().getString(),
                    "break",
                    ForgeRegistries.BLOCKS.getKey(state.getBlock()).toString(),
                    "" // 可以记录更多方块状态
            ));
        }
    }

}