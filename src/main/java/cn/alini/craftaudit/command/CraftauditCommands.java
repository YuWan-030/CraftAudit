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
                .then(Commands.literal("restore")
                        .then(Commands.argument("time", StringArgumentType.word())
                                .executes(ctx -> restore(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "time"),
                                        10))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 256))
                                        .executes(ctx -> restore(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "time"),
                                                IntegerArgumentType.getInteger(ctx, "radius")))
                                )
                        )
                )
                // 新增：/craftaudit undo 撤销最近一次回滚/恢复
                .then(Commands.literal("undo")
                        .executes(ctx -> undo(ctx.getSource()))
                )
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
                                        10))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 256))
                                        .executes(ctx -> restore(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "time"),
                                                IntegerArgumentType.getInteger(ctx, "radius")))
                                )
                        )
                )
                // 新增：/ca undo
                .then(Commands.literal("undo")
                        .executes(ctx -> undo(ctx.getSource()))
                )
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
    private static int restore(CommandSourceStack src, String timeStr, int radius) {
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

        int changed = RollbackService.restore(level, dim, center, radius, sinceMs, executor.getUUID());
        src.sendSuccess(() -> Component.literal(String.format("§3[CraftAudit] 恢复完成：%d 个方块。输入 /ca undo 撤销本次恢复。", changed)), true);
        return changed > 0 ? 1 : 0;
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
        src.sendSuccess(() -> Component.literal(String.format("§3[CraftAudit] 撤销完成：%d 个方块已恢复到回滚前状态。", changed)), true);
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