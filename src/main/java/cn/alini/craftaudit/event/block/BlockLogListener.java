package cn.alini.craftaudit.event.block;

import cn.alini.craftaudit.AuditModeManager;
import cn.alini.craftaudit.Craftaudit;
import cn.alini.craftaudit.storage.Database;
import cn.alini.craftaudit.storage.LogEntry;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

@Mod.EventBusSubscriber(modid = Craftaudit.MODID)
public class BlockLogListener {

    // 方块放置
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
        var state = player.level().getBlockState(pos);
        var block = state.getBlock();
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
                    ForgeRegistries.BLOCKS.getKey(block).toString(),
                    data
            ));
        }
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

    private static boolean isContainer(BlockEntity be) {
        var id = be.getType().toString().toLowerCase();
        return id.contains("chest") || id.contains("barrel") || id.contains("shulker") || id.contains("furnace");
    }
}