package cn.alini.craftaudit.event.block;

import cn.alini.craftaudit.Craftaudit;
import cn.alini.craftaudit.storage.Database;
import cn.alini.craftaudit.storage.LogEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

@Mod.EventBusSubscriber(modid = Craftaudit.MODID)
public class NaturalBreakListener {

    // 爆炸导致的方块破坏
    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (event.getLevel().isClientSide()) return;
        var level = event.getLevel();
        String dim = level.dimension().location().toString();
        long now = System.currentTimeMillis();

        for (BlockPos pos : event.getAffectedBlocks()) {
            BlockState st = level.getBlockState(pos);
            if (st.isAir()) continue; // 空气不记录
            Block block = st.getBlock();
            String targetId = ForgeRegistries.BLOCKS.getKey(block) == null ? "unknown" : ForgeRegistries.BLOCKS.getKey(block).toString();
            Database.get().insertAsync(new LogEntry(
                    now,
                    dim,
                    pos.getX(), pos.getY(), pos.getZ(),
                    "(爆炸)",
                    "natural_break",
                    targetId,
                    "{\"cause\":\"explosion\"}"
            ));
        }
    }

    // 液体放置替换了原方块（被淹没的火把/红石等）
    @SubscribeEvent
    public static void onFluidPlace(BlockEvent.FluidPlaceBlockEvent event) {
        var levelAcc = event.getLevel(); // LevelAccessor
        if (levelAcc.isClientSide()) return;

        BlockState oldSt = event.getOriginalState();
        if (oldSt.isAir()) return;

        // 如果被液体替换（原来不是液体/空气）
        if (!(oldSt.getBlock() instanceof LiquidBlock) && !(event.getNewState().getBlock() instanceof BaseFireBlock)) {
            String dim = (levelAcc instanceof Level lvl)
                    ? lvl.dimension().location().toString()
                    : "unknown";
            String targetId = ForgeRegistries.BLOCKS.getKey(oldSt.getBlock()).toString();
            BlockPos pos = event.getPos();
            Database.get().insertAsync(new LogEntry(
                    System.currentTimeMillis(),
                    dim,
                    pos.getX(), pos.getY(), pos.getZ(),
                    "(环境)",
                    "natural_break",
                    targetId,
                    "{\"cause\":\"fluid\"}"
            ));
        }
    }

    // 重力方块开始下落（原位置视为被“自然破坏”）
    @SubscribeEvent
    public static void onFallingSpawn(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof FallingBlockEntity falling)) return;
        var level = event.getLevel();
        if (level.isClientSide()) return;

        BlockState st = falling.getBlockState();
        String targetId = ForgeRegistries.BLOCKS.getKey(st.getBlock()).toString();
        BlockPos pos = falling.blockPosition();

        Database.get().insertAsync(new LogEntry(
                System.currentTimeMillis(),
                level.dimension().location().toString(),
                pos.getX(), pos.getY(), pos.getZ(),
                "(环境)",
                "natural_break",
                targetId,
                "{\"cause\":\"gravity\"}"
        ));
    }
}