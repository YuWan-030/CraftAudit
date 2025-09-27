package cn.alini.craftaudit.event.block;

import cn.alini.craftaudit.AuditModeManager;
import cn.alini.craftaudit.storage.Database;
import cn.alini.craftaudit.storage.LogEntry;
import cn.alini.craftaudit.util.NameLocalization;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.*;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Mod.EventBusSubscriber
public class BlockInspectListener {
    private static final int PAGE_SIZE = 7;
    private static final Map<UUID, BlockPos> auditQueryPos = new HashMap<>();
    private static final Map<UUID, String> auditQueryDim = new HashMap<>();
    private static final Map<UUID, Long> lastRightClickTime = new HashMap<>();
    private static final Map<UUID, BlockPos> lastRightClickPos = new HashMap<>();
    // 左键：只输出破坏和放置
    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;
        if (!AuditModeManager.isAuditing(player.getUUID())) return;
        var pos = event.getPos();
        var dimension = player.level().dimension().location().toString();
        auditQueryPos.put(player.getUUID(), pos);
        auditQueryDim.put(player.getUUID(), dimension);
        showBlockLogsPaged(player, dimension, pos.getX(), pos.getY(), pos.getZ(), 1);
        event.setCanceled(true);
    }

    // 右键：只输出交互日志（不含放置和破坏）
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;
        if (!AuditModeManager.isAuditing(player.getUUID())) return;
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;

        long now = System.currentTimeMillis() / 50; // tick
        var pos = event.getPos();
        var dimension = player.level().dimension().location().toString();
        if (lastRightClickTime.getOrDefault(player.getUUID(), 0L) == now
                && pos.equals(lastRightClickPos.get(player.getUUID()))) {
            return; // 同 tick 同坐标只处理一次
        }
        auditQueryPos.put(player.getUUID(), pos);
        auditQueryDim.put(player.getUUID(), dimension);
        showInteractLogsPaged(player, dimension, pos.getX(), pos.getY(), pos.getZ(), 1);
        event.setCanceled(true);
    }

    // 左键翻页
    public static void queryBlockLogPage(ServerPlayer player, int page) {
        BlockPos pos = auditQueryPos.get(player.getUUID());
        String dim = auditQueryDim.get(player.getUUID());
        if (pos == null || dim == null) {
            player.sendSystemMessage(Component.literal("§c请先左键方块进行查询！"));
            return;
        }
        showBlockLogsPaged(player, dim, pos.getX(), pos.getY(), pos.getZ(), page);
    }

    // 右键翻页
    public static void queryInteractLogPage(ServerPlayer player, int page) {
        BlockPos pos = auditQueryPos.get(player.getUUID());
        String dim = auditQueryDim.get(player.getUUID());
        if (pos == null || dim == null) {
            player.sendSystemMessage(Component.literal("§c请先右键方块进行查询！"));
            return;
        }
        showInteractLogsPaged(player, dim, pos.getX(), pos.getY(), pos.getZ(), page);
    }

    // 左键：只显示破坏和放置日志
    public static void showBlockLogsPaged(ServerPlayer player, String dimension, int x, int y, int z, int page) {
        String actionPattern = "break|place";
        int total = Database.get().countLogsAt(dimension, x, y, z, actionPattern);
        int totalPages = Math.max((total + PAGE_SIZE - 1) / PAGE_SIZE, 1);
        List<LogEntry> logs = Database.get().queryLogsAtPaged(dimension, x, y, z, actionPattern, page, PAGE_SIZE);

        player.sendSystemMessage(Component.literal(
                String.format("§7-----§3方块变更记录§7-----(%s x%d/y%d/z%d)", dimension, x, y, z)
        ));
        if (logs.isEmpty()) {
            player.sendSystemMessage(Component.literal("§7无记录。"));
        } else {
            for (LogEntry log : logs) {
                player.sendSystemMessage(formatBlockLogCoreProtect(log));
            }
        }
        player.sendSystemMessage(Component.literal("§7-----"));
        sendPageLine(player, x, y, z, page, totalPages, "block");
    }

    // 右键：只显示交互日志，不含破坏和放置
    public static void showInteractLogsPaged(ServerPlayer player, String dimension, int x, int y, int z, int page) {
        String actionPattern = "put|take|sign_edit|container_open|button_press|lever_pull|door_use";
        int total = Database.get().countLogsAt(dimension, x, y, z, actionPattern);
        int totalPages = Math.max((total + PAGE_SIZE - 1) / PAGE_SIZE, 1);
        List<LogEntry> logs = Database.get().queryLogsAtPaged(dimension, x, y, z, actionPattern, page, PAGE_SIZE);

        player.sendSystemMessage(Component.literal(
                String.format("§7-----§3交互记录§7-----(%s x%d/y%d/z%d)", dimension, x, y, z)
        ));
        if (logs.isEmpty()) {
            player.sendSystemMessage(Component.literal("§7无记录。"));
        } else {
            for (LogEntry log : logs) {
                player.sendSystemMessage(formatLogCoreProtect(log));
            }
        }
        player.sendSystemMessage(Component.literal("§7-----"));
        sendPageLine(player, x, y, z, page, totalPages, "interact");
    }

    // 方块日志（只输出放置、破坏，玩家名前加彩色正负号）
    private static MutableComponent formatBlockLogCoreProtect(LogEntry log) {
        double hoursAgo = (System.currentTimeMillis() - log.timeMillis()) / 1000.0 / 3600;
        String sign = log.action().equals("place") ? "+" : "-";
        ChatFormatting signColor = sign.equals("+") ? ChatFormatting.GOLD : ChatFormatting.RED;
        MutableComponent msg = Component.literal(String.format("%.1fh ago | ", hoursAgo)).withStyle(ChatFormatting.GRAY);
        msg.append(Component.literal(sign).withStyle(signColor));
        msg.append(Component.literal(" "));
        msg.append(Component.literal(log.player()).withStyle(ChatFormatting.BLUE));
        msg.append(Component.literal(" "));
        ChatFormatting actionColor = log.action().equals("place") ? ChatFormatting.WHITE : ChatFormatting.LIGHT_PURPLE;
        String actionText = log.action().equals("place") ? "放置了" : "破坏了";
        msg.append(Component.literal(actionText).withStyle(actionColor));
        msg.append(Component.literal(" "));
        msg.append(Component.literal(NameLocalization.blockName(log.target())).withStyle(ChatFormatting.AQUA));
        return msg;
    }

    // 通用交互日志（右键所有类型，玩家名前加彩色正负号）
    private static MutableComponent formatLogCoreProtect(LogEntry log) {
        double hoursAgo = (System.currentTimeMillis() - log.timeMillis()) / 1000.0 / 3600;
        String action = log.action();
        String sign = (action.equals("put") || action.equals("sign_edit") || action.equals("container_open")) ? "+" : "-";
        ChatFormatting signColor = sign.equals("+") ? ChatFormatting.GOLD : ChatFormatting.RED;
        String actionText;
        ChatFormatting actionColor;
        MutableComponent msg = Component.literal(String.format("%.1fh ago | ", hoursAgo)).withStyle(ChatFormatting.GRAY);
        msg.append(Component.literal(sign).withStyle(signColor));
        msg.append(Component.literal(" "));
        msg.append(Component.literal(log.player()).withStyle(ChatFormatting.BLUE));
        msg.append(Component.literal(" "));

        switch (action) {
            case "put":
            case "take": {
                actionText = action.equals("put") ? "存入" : "取出";
                actionColor = action.equals("put") ? ChatFormatting.YELLOW : ChatFormatting.GOLD;
                String itemId = "";
                int count = 0;
                try {
                    String data = log.data();
                    int i1 = data.indexOf("\"item\":\"") + 8;
                    int i2 = data.indexOf("\"", i1);
                    itemId = data.substring(i1, i2);
                    int c1 = data.indexOf("\"count\":") + 8;
                    int c2 = data.indexOf("}", c1);
                    count = Integer.parseInt(data.substring(c1, c2));
                } catch (Exception e) {
                    itemId = log.data();
                }
                String itemName = NameLocalization.itemName(itemId);
                msg.append(Component.literal(actionText).withStyle(actionColor));
                msg.append(Component.literal(" "));
                msg.append(Component.literal(count + "x ").withStyle(ChatFormatting.WHITE));
                msg.append(Component.literal(itemName).withStyle(ChatFormatting.AQUA));
                break;
            }
            case "sign_edit":
                actionText = "编辑了告示牌";
                actionColor = ChatFormatting.AQUA;
                msg.append(Component.literal(actionText).withStyle(actionColor));
                msg.append(Component.literal(" "));
                msg.append(Component.literal(NameLocalization.blockName(log.target())).withStyle(ChatFormatting.AQUA));
                msg.append(Component.literal(" 文本: " + log.data()).withStyle(ChatFormatting.GRAY));
                break;
            case "container_open":
                actionText = "打开了容器";
                actionColor = ChatFormatting.DARK_GREEN;
                msg.append(Component.literal(actionText).withStyle(actionColor));
                msg.append(Component.literal(" "));
                msg.append(Component.literal(NameLocalization.blockName(log.target())).withStyle(ChatFormatting.AQUA));
                break;
            case "button_press":
                actionText = "按下了按钮";
                actionColor = ChatFormatting.DARK_AQUA;
                msg.append(Component.literal(actionText).withStyle(actionColor));
                msg.append(Component.literal(" "));
                msg.append(Component.literal(NameLocalization.blockName(log.target())).withStyle(ChatFormatting.AQUA));
                break;
            case "lever_pull":
                actionText = "拉动了拉杆";
                actionColor = ChatFormatting.DARK_AQUA;
                msg.append(Component.literal(actionText).withStyle(actionColor));
                msg.append(Component.literal(" "));
                msg.append(Component.literal(NameLocalization.blockName(log.target())).withStyle(ChatFormatting.AQUA));
                break;
            case "door_use":
                actionText = "使用了门";
                actionColor = ChatFormatting.DARK_AQUA;
                msg.append(Component.literal(actionText).withStyle(actionColor));
                msg.append(Component.literal(" "));
                msg.append(Component.literal(NameLocalization.blockName(log.target())).withStyle(ChatFormatting.AQUA));
                break;
            default:
                actionText = action;
                actionColor = ChatFormatting.GRAY;
                msg.append(Component.literal(actionText).withStyle(actionColor));
                msg.append(Component.literal(" "));
                msg.append(Component.literal(log.target()).withStyle(ChatFormatting.AQUA));
        }
        return msg;
    }

    // 分页按钮
    private static void sendPageLine(ServerPlayer player, int x, int y, int z, int page, int totalPages, String type) {
        MutableComponent pageLine = Component.literal(String.format("第 %d/%d 页 ", page, totalPages)).withStyle(ChatFormatting.GRAY);

        // 选择命令前缀
        String cmd = type.equals("block") ? "/ca blocklog " : "/ca log ";

        if (page > 1) {
            pageLine.append(
                    Component.literal("◀ ").withStyle(style -> style
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd + (page - 1)))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("上一页")))
                            .withColor(ChatFormatting.AQUA)
                    )
            );
        }
        pageLine.append(Component.literal("("));
        for (int i = 1; i <= totalPages; i++) {
            if (i == page) {
                pageLine.append(Component.literal(i + "").withStyle(ChatFormatting.YELLOW));
            } else {
                int finalI = i;
                pageLine.append(
                        Component.literal(finalI + "").withStyle(style -> style
                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd + finalI))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("跳转到第 " + finalI + " 页")))
                                .withColor(ChatFormatting.AQUA)
                        )
                );
            }
            if (i < totalPages) pageLine.append(Component.literal("|").withStyle(ChatFormatting.GRAY));
        }
        pageLine.append(Component.literal(") "));
        if (page < totalPages) {
            pageLine.append(
                    Component.literal("▶").withStyle(style -> style
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd + (page + 1)))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("下一页")))
                            .withColor(ChatFormatting.AQUA)
                    )
            );
        }
        player.sendSystemMessage(pageLine);
    }
}