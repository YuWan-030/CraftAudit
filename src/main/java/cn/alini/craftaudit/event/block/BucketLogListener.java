package cn.alini.craftaudit.event.block;

import cn.alini.craftaudit.AuditModeManager;
import cn.alini.craftaudit.Craftaudit;
import cn.alini.craftaudit.storage.Database;
import cn.alini.craftaudit.storage.LogEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Bucketable;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.CandleCakeBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

@Mod.EventBusSubscriber(modid = Craftaudit.MODID)
public class BucketLogListener {

    // 用空桶装流体（桶装水/岩浆/细雪）
    @SubscribeEvent
    public static void onBucketFill(PlayerInteractEvent.RightClickBlock event) {
        var p = event.getEntity();
        if (!(p instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;
        if (AuditModeManager.isAuditing(player.getUUID())) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;

        ItemStack held = event.getItemStack();
        if (held.isEmpty() || held.getItem() != Items.BUCKET) return;

        Level level = player.level();
        BlockPos pos = event.getPos();
        BlockState st = level.getBlockState(pos);

        String fluidId = null;
        if (st.getBlock() instanceof LiquidBlock lb) {
            // 仅源方块（level==0）可装桶
            try {
                if (st.getValue(LiquidBlock.LEVEL) == 0) {
                    fluidId = ForgeRegistries.FLUIDS.getKey(lb.getFluid()).toString();
                }
            } catch (Exception ignored) {}
        } else if (st.getBlock() instanceof PowderSnowBlock) {
            fluidId = ForgeRegistries.BLOCKS.getKey(st.getBlock()).toString(); // minecraft:powder_snow
        }

        if (fluidId != null) {
            Database.get().insertAsync(new LogEntry(
                    System.currentTimeMillis(),
                    level.dimension().location().toString(),
                    pos.getX(), pos.getY(), pos.getZ(),
                    player.getName().getString(),
                    "bucket_fill",
                    fluidId,
                    ""
            ));
        }
    }

    // 用满桶倒流体（水/岩浆/细雪）
    @SubscribeEvent
    public static void onBucketEmpty(PlayerInteractEvent.RightClickBlock event) {
        var p = event.getEntity();
        if (!(p instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;
        if (AuditModeManager.isAuditing(player.getUUID())) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;

        ItemStack held = event.getItemStack();
        if (held.isEmpty() || !(held.getItem() instanceof BucketItem)) return;

        // 空桶在上一个监听里处理；这里处理“满桶”
        if (held.getItem() == Items.BUCKET) return;

        String fluidId = ForgeRegistries.ITEMS.getKey(held.getItem()).toString(); // 如 minecraft:water_bucket
        // 记录倒出的最终坐标为点击面的相邻格
        BlockPos placePos = event.getPos().relative(event.getFace());

        Database.get().insertAsync(new LogEntry(
                System.currentTimeMillis(),
                player.level().dimension().location().toString(),
                placePos.getX(), placePos.getY(), placePos.getZ(),
                player.getName().getString(),
                "bucket_empty",
                fluidId,
                ""
        ));
    }

    // 空桶装生物（鱼、蝾螈等实现 Bucketable）
    @SubscribeEvent
    public static void onBucketCatch(PlayerInteractEvent.EntityInteractSpecific event) {
        var p = event.getEntity();
        if (!(p instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;
        if (AuditModeManager.isAuditing(player.getUUID())) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;

        ItemStack held = player.getMainHandItem();
        if (held.isEmpty() || held.getItem() != Items.BUCKET) return;

        Entity target = event.getTarget();
        if (!(target instanceof Bucketable)) return;

        BlockPos pos = target.blockPosition();
        String entId = ForgeRegistries.ENTITY_TYPES.getKey(target.getType()).toString();
        Database.get().insertAsync(new LogEntry(
                System.currentTimeMillis(),
                player.level().dimension().location().toString(),
                pos.getX(), pos.getY(), pos.getZ(),
                player.getName().getString(),
                "bucket_catch",
                entId,
                ""
        ));
    }

    // 空桶挤牛奶（牛、山羊）
    @SubscribeEvent
    public static void onBucketMilk(PlayerInteractEvent.EntityInteractSpecific event) {
        var p = event.getEntity();
        if (!(p instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;
        if (AuditModeManager.isAuditing(player.getUUID())) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;

        ItemStack held = player.getMainHandItem();
        if (held.isEmpty() || held.getItem() != Items.BUCKET) return;

        Entity target = event.getTarget();
        if (!(target instanceof Cow || target instanceof Goat)) return;

        BlockPos pos = target.blockPosition();
        String entId = ForgeRegistries.ENTITY_TYPES.getKey(target.getType()).toString();
        Database.get().insertAsync(new LogEntry(
                System.currentTimeMillis(),
                player.level().dimension().location().toString(),
                pos.getX(), pos.getY(), pos.getZ(),
                player.getName().getString(),
                "bucket_milk",
                entId,
                ""
        ));
    }
}