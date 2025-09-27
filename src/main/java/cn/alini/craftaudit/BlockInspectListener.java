package cn.alini.craftaudit;

import cn.alini.craftaudit.storage.Database;
import cn.alini.craftaudit.storage.LogEntry;
import cn.alini.craftaudit.util.NameLocalization;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.network.chat.Component;

import java.util.List;

@Mod.EventBusSubscriber(modid = Craftaudit.MODID)
public class BlockInspectListener {

    private static final int PAGE_SIZE = 5; // 可改为7等
    private static final int DEFAULT_PAGE = 1;

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!AuditModeManager.isAuditing(player.getUUID())) return;

        var pos = event.getPos();
        var dimension = player.level().dimension().location().toString();

        List<LogEntry> logs = Database.get().queryLogsAtPaged(dimension, pos.getX(), pos.getY(), pos.getZ(), "break|place", DEFAULT_PAGE, PAGE_SIZE);

        sendBlockLogs(player, logs);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!AuditModeManager.isAuditing(player.getUUID())) return;

        var pos = event.getPos();
        var dimension = player.level().dimension().location().toString();

        List<LogEntry> logs = Database.get().queryLogsAtPaged(dimension, pos.getX(), pos.getY(), pos.getZ(), "put|take", DEFAULT_PAGE, PAGE_SIZE);

        sendContainerLogs(player, logs);
        event.setCanceled(true);
    }

    // 美化方块日志输出
    private static void sendBlockLogs(ServerPlayer player, List<LogEntry> logs) {
        if (logs.isEmpty()) {
            player.sendSystemMessage(Component.literal("[craftaudit] 没有查询到相关方块日志。"));
        } else {
            for (LogEntry log : logs) {
                double daysAgo = (System.currentTimeMillis() - log.timeMillis()) / 1000.0 / 3600 / 24;
                String actionText = log.action().equals("place") ? "放置了" : "破坏了";
                String blockName = NameLocalization.blockName(log.target());
                player.sendSystemMessage(Component.literal(String.format("%.2f/d 前 - %s %s %s。", daysAgo, log.player(), actionText, blockName)));
            }
        }
    }

    // 美化容器日志输出
    private static void sendContainerLogs(ServerPlayer player, List<LogEntry> logs) {
        if (logs.isEmpty()) {
            player.sendSystemMessage(Component.literal("[craftaudit] 没有查询到相关容器日志。"));
        } else {
            for (LogEntry log : logs) {
                double daysAgo = (System.currentTimeMillis() - log.timeMillis()) / 1000.0 / 3600 / 24;
                String actionText = log.action().equals("put") ? "存入" : "取出";
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
                player.sendSystemMessage(Component.literal(String.format("%.2f/d 前 - %s %s %dx %s。", daysAgo, log.player(), actionText, count, itemName)));
            }
        }
    }
}