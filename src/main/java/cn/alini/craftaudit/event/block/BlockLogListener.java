package cn.alini.craftaudit.event.block;

import cn.alini.craftaudit.AuditModeManager;
import cn.alini.craftaudit.Craftaudit;
import cn.alini.craftaudit.storage.Database;
import cn.alini.craftaudit.storage.LogEntry;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

@Mod.EventBusSubscriber(modid = Craftaudit.MODID)
public class BlockLogListener {

    // 方块放置（仅 BlockItem）
    @SubscribeEvent
    public static void onBlockPlace(PlayerInteractEvent.RightClickBlock event) {
        var player = event.getEntity();
        if (player == null || player.level().isClientSide()) return;
        // 禁止审计状态的玩家写入
        if (AuditModeManager.isAuditing(player.getUUID())) return;

        var item = event.getItemStack();
        if (!item.isEmpty() && item.getItem() instanceof net.minecraft.world.item.BlockItem blockItem) {
            var pos = event.getPos().relative(event.getFace());
            var block = blockItem.getBlock();
            Database.get().insertAsync(new LogEntry(
                    System.currentTimeMillis(),
                    player.level().dimension().location().toString(),
                    pos.getX(), pos.getY(), pos.getZ(),
                    player.getName().getString(),
                    "place",
                    ForgeRegistries.BLOCKS.getKey(block).toString(),
                    ""
            ));
        }
    }

    // 告示牌文本修改
    @SubscribeEvent
    public static void onSignEdit(PlayerInteractEvent.RightClickBlock event) {
        var player = event.getEntity();
        if (player == null || player.level().isClientSide()) return;
        if (AuditModeManager.isAuditing(player.getUUID())) return;

        var pos = event.getPos();
        BlockEntity be = player.level().getBlockEntity(pos);
        if (be instanceof SignBlockEntity sign) {
            List<Component> frontLines = List.of(sign.getFrontText().getMessages(sign.isWaxed()));
            List<Component> backLines = List.of(sign.getBackText().getMessages(sign.isWaxed()));
            String front = frontLines.stream().map(Component::getString).collect(java.util.stream.Collectors.joining(" | "));
            String back = backLines.stream().map(Component::getString).collect(java.util.stream.Collectors.joining(" | "));
            String data = "Front: " + front + "; Back: " + back;
            Database.get().insertAsync(new LogEntry(
                    System.currentTimeMillis(),
                    player.level().dimension().location().toString(),
                    pos.getX(), pos.getY(), pos.getZ(),
                    player.getName().getString(),
                    "sign_edit",
                    ForgeRegistries.BLOCKS.getKey(player.level().getBlockState(pos).getBlock()).toString(),
                    data
            ));
        }
    }

    // 使用打火石点燃“状态型”方块（不生成火方块的情况）：营火、蜡烛/蜡烛蛋糕、TNT
    @SubscribeEvent
    public static void onUseFlintAndSteel(PlayerInteractEvent.RightClickBlock event) {
        var player = event.getEntity();
        if (player == null || player.level().isClientSide()) return;
        if (AuditModeManager.isAuditing(player.getUUID())) return;
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;

        ItemStack held = event.getItemStack();
        if (held.isEmpty() || !(held.getItem() instanceof FlintAndSteelItem)) return;

        Level level = player.level();
        BlockPos pos = event.getPos();
        Block block = level.getBlockState(pos).getBlock();

        String action = "ignite";
        String targetId = null;
        BlockPos logPos = pos;

        // 营火
        if (block instanceof CampfireBlock) {
            targetId = ForgeRegistries.BLOCKS.getKey(block).toString();
        }
        // 蜡烛或蜡烛蛋糕
        else if (block instanceof CandleBlock || block instanceof CandleCakeBlock) {
            targetId = ForgeRegistries.BLOCKS.getKey(block).toString();
        }
        // TNT 引燃
        else if (block instanceof TntBlock) {
            targetId = ForgeRegistries.BLOCKS.getKey(block).toString();
        }

        if (targetId != null) {
            Database.get().insertAsync(new LogEntry(
                    System.currentTimeMillis(),
                    level.dimension().location().toString(),
                    logPos.getX(), logPos.getY(), logPos.getZ(),
                    player.getName().getString(),
                    action,
                    targetId,
                    ""
            ));
            // 不取消事件，让原版继续处理
        }
    }

    // 放置火（火或灵魂火）：由打火石或火焰弹等在相邻格生成的火方块，用放置事件拿到最终方块和坐标
    @SubscribeEvent
    public static void onFirePlaced(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) return;
        if (player.level().isClientSide()) return;
        if (AuditModeManager.isAuditing(player.getUUID())) return;

        BlockState placed = event.getPlacedBlock();
        Block block = placed.getBlock();
        if (!(block instanceof BaseFireBlock)) return; // 只记录火/灵魂火

        BlockPos pos = event.getPos();
        Database.get().insertAsync(new LogEntry(
                System.currentTimeMillis(),
                player.level().dimension().location().toString(),
                pos.getX(), pos.getY(), pos.getZ(),
                player.getName().getString(),
                "ignite",
                ForgeRegistries.BLOCKS.getKey(block).toString(),
                ""
        ));
    }

    // 特殊方块交互（按钮、拉杆、门等）
    @SubscribeEvent
    public static void onSpecialBlockUse(PlayerInteractEvent.RightClickBlock event) {
        var player = event.getEntity();
        if (player == null || player.level().isClientSide()) return;
        if (AuditModeManager.isAuditing(player.getUUID())) return;

        var pos = event.getPos();
        var state = player.level().getBlockState(pos);
        var block = state.getBlock();
        String action = null;
        if (block.getDescriptionId().contains("button")) action = "button_press";
        else if (block.getDescriptionId().contains("lever")) action = "lever_pull";
        else if (block.getDescriptionId().contains("door")) action = "door_use";

        if (action != null) {
            Database.get().insertAsync(new LogEntry(
                    System.currentTimeMillis(),
                    player.level().dimension().location().toString(),
                    pos.getX(), pos.getY(), pos.getZ(),
                    player.getName().getString(),
                    action,
                    ForgeRegistries.BLOCKS.getKey(block).toString(),
                    ""
            ));
        }
    }
}