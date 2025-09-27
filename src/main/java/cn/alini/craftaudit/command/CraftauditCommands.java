package cn.alini.craftaudit.command;

import cn.alini.craftaudit.AuditModeManager;
import cn.alini.craftaudit.Craftaudit;
import cn.alini.craftaudit.config.Config;
import cn.alini.craftaudit.event.block.BlockInspectListener;
import cn.alini.craftaudit.rollback.RollbackService;
import cn.alini.craftaudit.rollback.UndoManager;
import cn.alini.craftaudit.storage.Database;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mod.EventBusSubscriber(modid = Craftaudit.MODID)
public final class CraftauditCommands {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();

        // /craftaudit ...
        d.register(Commands.literal("craftaudit")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("status").executes(ctx -> report(ctx.getSource())))
                .then(Commands.literal("inspect").executes(ctx -> toggleAudit(ctx.getSource())))
                .then(Commands.literal("log")
                        .executes(ctx -> inspectAudit(ctx.getSource(), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1, 100))
                                .executes(ctx -> inspectAudit(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "page")))
                        )
                )
                .then(Commands.literal("blocklog")
                        .executes(ctx -> inspectBlockAudit(ctx.getSource(), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1, 100))
                                .executes(ctx -> inspectBlockAudit(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "page")))
                        )
                )
                .then(Commands.literal("near")
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 256))
                                .then(Commands.argument("time", StringArgumentType.word())
                                        .executes(ctx -> near(
                                                ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "radius"),
                                                StringArgumentType.getString(ctx, "time"),
                                                1))
                                        .then(Commands.argument("page", IntegerArgumentType.integer(1, 100))
                                                .executes(ctx -> near(
                                                        ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "radius"),
                                                        StringArgumentType.getString(ctx, "time"),
                                                        IntegerArgumentType.getInteger(ctx, "page")))
                                        )
                                )
                        )
                )
                .then(Commands.literal("rollback")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .then(Commands.argument("time", StringArgumentType.word())
                                        .executes(ctx -> rollback(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "player"),
                                                StringArgumentType.getString(ctx, "time"),
                                                10))
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 256))
                                                .executes(ctx -> rollback(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "player"),
                                                        StringArgumentType.getString(ctx, "time"),
                                                        IntegerArgumentType.getInteger(ctx, "radius")))
                                        )
                                )
                        )
                )
                // 新：restore <time> [radius] [type]
                .then(Commands.literal("restore")
                        .then(Commands.argument("time", StringArgumentType.word())
                                // time
                                .executes(ctx -> restore(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "time"),
                                        10, null))
                                // time + radius
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 256))
                                        .executes(ctx -> restore(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "time"),
                                                IntegerArgumentType.getInteger(ctx, "radius"),
                                                null))
                                        // time + radius + type
                                        .then(Commands.argument("type", StringArgumentType.word())
                                                .executes(ctx -> restore(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "time"),
                                                        IntegerArgumentType.getInteger(ctx, "radius"),
                                                        StringArgumentType.getString(ctx, "type")))
                                        )
                                )
                                // time + type（无 radius）
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .executes(ctx -> restore(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "time"),
                                                10,
                                                StringArgumentType.getString(ctx, "type")))
                                )
                        )
                )
                // 新：purge <time> 清理早于指定时间的日志
                .then(Commands.literal("purge")
                        .then(Commands.argument("time", StringArgumentType.word())
                                .executes(ctx -> purge(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "time")))
                        )
                )
                .then(Commands.literal("undo")
                        .executes(ctx -> undo(ctx.getSource()))
                )
                // /craftaudit help
                .then(Commands.literal("help").executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    src.sendSuccess(() -> Component.literal(
                            "§3[CraftAudit] 指令列表：\n" +
                                    "§b/craftaudit status §7- 显示插件状态\n" +
                                    "§b/craftaudit inspect §7- 切换审计模式\n" +
                                    "§b/craftaudit log [page] §7- 查看交互日志\n" +
                                    "§b/craftaudit blocklog [page] §7- 查看方块日志\n" +
                                    "§b/craftaudit near <radius> <time> [page] §7- 查看附近方块日志\n" +
                                    "§b/craftaudit rollback <player> <time> [radius] §7- 回滚玩家破坏的方块\n" +
                                    "§b/craftaudit restore <time> [radius] [type] §7- 恢复破坏/击杀记录（支持 type 过滤）\n" +
                                    "§b/craftaudit purge <time> §7- 清理早于指定时间的日志（不可撤销）\n" +
                                    "§b/craftaudit undo §7- 撤销上次回滚/恢复\n" +
                                    "\n" +
                                    "§b/ca ... §7- /craftaudit 的简写"
                    ), false);
                    return 1;
                }))
        );

        // /ca ...
        d.register(Commands.literal("ca")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("i").executes(ctx -> toggleAudit(ctx.getSource())))
                .then(Commands.literal("log")
                        .executes(ctx -> inspectAudit(ctx.getSource(), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1, 100))
                                .executes(ctx -> inspectAudit(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "page")))
                        )
                )
                .then(Commands.literal("blocklog")
                        .executes(ctx -> inspectBlockAudit(ctx.getSource(), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1, 100))
                                .executes(ctx -> inspectBlockAudit(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "page")))
                        )
                )
                .then(Commands.literal("near")
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 256))
                                .then(Commands.argument("time", StringArgumentType.word())
                                        .executes(ctx -> near(
                                                ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "radius"),
                                                StringArgumentType.getString(ctx, "time"),
                                                1))
                                        .then(Commands.argument("page", IntegerArgumentType.integer(1, 100))
                                                .executes(ctx -> near(
                                                        ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "radius"),
                                                        StringArgumentType.getString(ctx, "time"),
                                                        IntegerArgumentType.getInteger(ctx, "page")))
                                        )
                                )
                        )
                )
                .then(Commands.literal("rollback")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .then(Commands.argument("time", StringArgumentType.word())
                                        .executes(ctx -> rollback(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "player"),
                                                StringArgumentType.getString(ctx, "time"),
                                                10))
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 256))
                                                .executes(ctx -> rollback(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "player"),
                                                        StringArgumentType.getString(ctx, "time"),
                                                        IntegerArgumentType.getInteger(ctx, "radius")))
                                        )
                                )
                        )
                )
                .then(Commands.literal("restore")
                        .then(Commands.argument("time", StringArgumentType.word())
                                .executes(ctx -> restore(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "time"),
                                        10, null))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 256))
                                        .executes(ctx -> restore(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "time"),
                                                IntegerArgumentType.getInteger(ctx, "radius"),
                                                null))
                                        .then(Commands.argument("type", StringArgumentType.word())
                                                .executes(ctx -> restore(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "time"),
                                                        IntegerArgumentType.getInteger(ctx, "radius"),
                                                        StringArgumentType.getString(ctx, "type")))
                                        )
                                )
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .executes(ctx -> restore(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "time"),
                                                10,
                                                StringArgumentType.getString(ctx, "type")))
                                )
                        )
                )
                // 新：/ca purge <time>
                .then(Commands.literal("purge")
                        .then(Commands.argument("time", StringArgumentType.word())
                                .executes(ctx -> purge(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "time")))
                        )
                )
                .then(Commands.literal("undo")
                        .executes(ctx -> undo(ctx.getSource()))
                )
                // /ca help
                .then(Commands.literal("help").executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    src.sendSuccess(() -> Component.literal(
                            "§3[CraftAudit] 指令列表：\n" +
                                    "§b/ca status §7- 显示插件状态\n" +
                                    "§b/ca i §7- 切换审计模式\n" +
                                    "§b/ca log [page] §7- 查看交互日志\n" +
                                    "§b/ca blocklog [page] §7- 查看方块日志\n" +
                                    "§b/ca near <radius> <time> [page] §7- 查看附近方块日志\n" +
                                    "§b/ca rollback <player> <time> [radius] §7- 回滚玩家破坏的方块\n" +
                                    "§b/ca restore <time> [radius] [type] §7- 恢复破坏/击杀记录（支持 type 过滤）\n" +
                                    "§b/ca purge <time> §7- 清理早于指定时间的日志（不可撤销）\n" +
                                    "§b/ca undo §7- 撤销上次回滚/恢复\n" +
                                    "\n" +
                                    "§b/craftaudit ... §7- /ca 的全称"
                    ), false);
                    return 1;
                }))
        );
    }

    private static int report(CommandSourceStack src) {
        boolean connected = Database.isConnected();
        boolean ok = connected && Database.get().ping();
        String mode = Config.isSqlite() ? "SQLITE" : "MYSQL";
        src.sendSuccess(() -> Component.literal("§3[CraftAudit] 模式：" + mode + " | 状态：" + ok), false);
        return ok ? 1 : 0;
    }

    private static int toggleAudit(CommandSourceStack src) {
        var player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("§c[CraftAudit] 只能由玩家使用该命令！"));
            return 0;
        }
        UUID uuid = player.getUUID();
        if (AuditModeManager.isAuditing(uuid)) {
            AuditModeManager.exit(uuid);
            src.sendSuccess(() -> Component.literal("§3[CraftAudit] 已退出审计模式。"), false);
        } else {
            AuditModeManager.enter(uuid);
            src.sendSuccess(() -> Component.literal("§3[CraftAudit] 已进入审计模式。"), false);
        }
        return 1;
    }

    private static int inspectAudit(CommandSourceStack src, int page) {
        var player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("§c[CraftAudit] 只能由玩家使用该命令！"));
            return 0;
        }
        UUID uuid = player.getUUID();
        if (!AuditModeManager.isAuditing(uuid)) {
            AuditModeManager.enter(uuid);
            src.sendSuccess(() -> Component.literal("§3[CraftAudit] 已进入审计模式。"), false);
        }
        BlockInspectListener.queryInteractLogPage(player, page);
        return 1;
    }

    private static int inspectBlockAudit(CommandSourceStack src, int page) {
        var player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("§c[CraftAudit] 只能由玩家使用该命令！"));
            return 0;
        }
        UUID uuid = player.getUUID();
        if (!AuditModeManager.isAuditing(uuid)) {
            AuditModeManager.enter(uuid);
            src.sendSuccess(() -> Component.literal("§3[CraftAudit] 已进入审计模式。"), false);
        }
        BlockInspectListener.queryBlockLogPage(player, page);
        return 1;
    }

    // near
    private static int near(CommandSourceStack src, int radius, String timeStr, int page) {
        var player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("§c[CraftAudit] 只能由玩家使用该命令！"));
            return 0;
        }
        long durMs = parseDurationMillis(timeStr);
        if (durMs <= 0L) {
            src.sendFailure(Component.literal("§c时间格式无效！示例：5d、12h、30m、45s"));
            return 0;
        }
        long sinceMs = System.currentTimeMillis() - durMs;
        BlockInspectListener.showNearbyLogsPaged(player, radius, sinceMs, page, timeStr);
        return 1;
    }

    // rollback（带撤销快照）
    private static int rollback(CommandSourceStack src, String playerName, String timeStr, int radius) {
        var executor = src.getPlayer();
        if (executor == null) {
            src.sendFailure(Component.literal("§c[CraftAudit] 只能由玩家使用该命令！"));
            return 0;
        }
        long durMs = parseDurationMillis(timeStr);
        if (durMs <= 0L) {
            src.sendFailure(Component.literal("§c时间格式无效！示例：5d、12h、30m、45s"));
            return 0;
        }
        long sinceMs = System.currentTimeMillis() - durMs;

        ServerLevel level = executor.serverLevel();
        String dim = level.dimension().location().toString();
        BlockPos center = executor.blockPosition();

        int changed = RollbackService.rollback(level, dim, center, radius, sinceMs, playerName, executor.getUUID());
        src.sendSuccess(() -> Component.literal(String.format("§3[CraftAudit] 回滚完成：%d 个方块。输入 /ca undo 撤销本次回滚。", changed)), true);
        return changed > 0 ? 1 : 0;
    }

    // restore（带撤销快照）
    // 支持 type：break | natural | explosion | fluid | gravity | kill | kill:<entity_id>
    private static int restore(CommandSourceStack src, String timeStr, int radius, String typeOpt) {
        var executor = src.getPlayer();
        if (executor == null) {
            src.sendFailure(Component.literal("§c[CraftAudit] 只能由玩家使用该命令！"));
            return 0;
        }
        long durMs = parseDurationMillis(timeStr);
        if (durMs <= 0L) {
            src.sendFailure(Component.literal("§c时间格式无效！示例：5d、12h、30m、45s"));
            return 0;
        }
        long sinceMs = System.currentTimeMillis() - durMs;

        ServerLevel level = executor.serverLevel();
        String dim = level.dimension().location().toString();
        BlockPos center = executor.blockPosition();

        if (typeOpt == null) {
            int changed = RollbackService.restoreBreaks(level, dim, center, radius, sinceMs, List.of("break","natural_break"), null, executor.getUUID());
            src.sendSuccess(() -> Component.literal(String.format("§3[CraftAudit] 恢复完成（所有破坏）：%d 个方块。/ca undo 可撤销。", changed)), true);
            return changed > 0 ? 1 : 0;
        }

        String t = typeOpt.toLowerCase();
        switch (t) {
            case "break": {
                int changed = RollbackService.restoreBreaks(level, dim, center, radius, sinceMs, List.of("break"), null, executor.getUUID());
                src.sendSuccess(() -> Component.literal(String.format("§3[CraftAudit] 恢复完成（玩家破坏）：%d 个方块。/ca undo 可撤销。", changed)), true);
                return changed > 0 ? 1 : 0;
            }
            case "natural":
            case "natural_break": {
                int changed = RollbackService.restoreBreaks(level, dim, center, radius, sinceMs, List.of("natural_break"), null, executor.getUUID());
                src.sendSuccess(() -> Component.literal(String.format("§3[CraftAudit] 恢复完成（环境破坏）：%d 个方块。/ca undo 可撤销。", changed)), true);
                return changed > 0 ? 1 : 0;
            }
            case "explosion":
            case "fluid":
            case "gravity": {
                int changed = RollbackService.restoreBreaks(level, dim, center, radius, sinceMs, List.of("natural_break"), t, executor.getUUID());
                src.sendSuccess(() -> Component.literal(String.format("§3[CraftAudit] 恢复完成（环境破坏: %s）：%d 个方块。/ca undo 可撤销。", t, changed)), true);
                return changed > 0 ? 1 : 0;
            }
            default:
                if (t.equals("kill") || t.startsWith("kill:")) {
                    String entityFilter = null;
                    if (t.startsWith("kill:")) {
                        entityFilter = t.substring("kill:".length());
                        if (entityFilter.isBlank()) entityFilter = null;
                    }
                    int spawned = RollbackService.restoreKills(level, dim, center, radius, sinceMs, entityFilter, executor.getUUID());
                    String scope = (entityFilter == null) ? "所有击杀" : ("击杀: " + entityFilter);
                    src.sendSuccess(() -> Component.literal(String.format("§3[CraftAudit] 恢复完成（%s）：生成 %d 个实体。/ca undo 可撤销。", scope, spawned)), true);
                    return spawned > 0 ? 1 : 0;
                } else {
                    src.sendFailure(Component.literal("§c未知的 type！可选：break | natural | explosion | fluid | gravity | kill | kill:<entity_id>"));
                    return 0;
                }
        }
    }

    // purge：清理早于指定时间的日志（不可撤销）
    private static int purge(CommandSourceStack src, String timeStr) {
        long durMs = parseDurationMillis(timeStr);
        if (durMs <= 0L) {
            src.sendFailure(Component.literal("§c时间格式无效！示例：30d、12h、90m、300s"));
            return 0;
        }
        long beforeMs = System.currentTimeMillis() - durMs;

        if (!Database.isConnected()) {
            src.sendFailure(Component.literal("§c数据库未连接，无法清理。"));
            return 0;
        }
        int deleted = Database.get().deleteLogsBefore(beforeMs);
        if (deleted < 0) {
            src.sendFailure(Component.literal("§c清理日志失败，请查看控制台。"));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("§3[CraftAudit] 已清理 " + deleted + " 条早于 " + timeStr + " 的日志记录。"), true);
        return 1;
    }

    // undo
    private static int undo(CommandSourceStack src) {
        var executor = src.getPlayer();
        if (executor == null) {
            src.sendFailure(Component.literal("§c[CraftAudit] 只能由玩家使用该命令！"));
            return 0;
        }
        if (!UndoManager.hasUndo(executor.getUUID())) {
            src.sendFailure(Component.literal("§c没有可撤销的回滚/恢复记录。"));
            return 0;
        }
        int changed = UndoManager.applyUndo(executor);
        src.sendSuccess(() -> Component.literal(String.format("§3[CraftAudit] 撤销完成：%d 项更改已恢复。", changed)), true);
        return changed > 0 ? 1 : 0;
    }

    // parse duration
    private static final Pattern DURATION_PATTERN = Pattern.compile("^(\\d+)([smhdSMHD])$");
    private static long parseDurationMillis(String s) {
        if (s == null) return -1L;
        Matcher m = DURATION_PATTERN.matcher(s.trim());
        if (!m.matches()) return -1L;
        long val = Long.parseLong(m.group(1));
        char unit = Character.toLowerCase(m.group(2).charAt(0));
        return switch (unit) {
            case 's' -> val * 1000L;
            case 'm' -> val * 60_000L;
            case 'h' -> val * 3_600_000L;
            case 'd' -> val * 86_400_000L;
            default -> -1L;
        };
    }
}