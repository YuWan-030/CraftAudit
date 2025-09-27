package cn.alini.craftaudit.command;

import cn.alini.craftaudit.AuditModeManager;
import cn.alini.craftaudit.Craftaudit;
import cn.alini.craftaudit.config.Config;
import cn.alini.craftaudit.event.block.BlockInspectListener;
import cn.alini.craftaudit.storage.Database;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.UUID;

@Mod.EventBusSubscriber(modid = Craftaudit.MODID)
public final class CraftauditCommands {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();

        // /craftaudit status
        d.register(Commands.literal("craftaudit")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("status").executes(ctx -> report(ctx.getSource())))
                // /craftaudit inspect 只切换审计模式（开关）
                .then(Commands.literal("inspect")
                        .executes(ctx -> toggleAudit(ctx.getSource()))
                )
                // /craftaudit log [page] 查询日志
                .then(Commands.literal("log")
                        .executes(ctx -> inspectAudit(ctx.getSource(), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1, 100))
                                .executes(ctx -> inspectAudit(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "page")))
                        )
                )
        );

        // /ca i 为 inspect 的别名
        d.register(Commands.literal("ca")
                .requires(src -> src.hasPermission(2))
                // /ca i == /craftaudit inspect
                .then(Commands.literal("i")
                        .executes(ctx -> toggleAudit(ctx.getSource()))
                )
                // /ca log == /craftaudit log
                .then(Commands.literal("log")
                        .executes(ctx -> inspectAudit(ctx.getSource(), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1, 100))
                                .executes(ctx -> inspectAudit(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "page")))
                        )
                )
        );
    }

    private static int report(CommandSourceStack src) {
        boolean connected = Database.isConnected();
        boolean ok = connected && Database.get().ping();

        String mode = Config.isSqlite() ? "SQLITE" : "MYSQL";

        src.sendSuccess(
                () -> Component.literal("§3[CraftAudit] 模式：" + mode + " | 状态：" + ok),
                false
        );
        return ok ? 1 : 0;
    }

    /**
     * 切换审计模式（开/关，调用时只提示状态，不查日志）
     */
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

    /**
     * 查询指定页的方块/容器日志（默认查玩家脚下方块）
     */
    private static int inspectAudit(CommandSourceStack src, int page) {
        var player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("§c[CraftAudit] 只能由玩家使用该命令！"));
            return 0;
        }
        // 查询日志时自动开启审计模式（如果没开）
        UUID uuid = player.getUUID();
        if (!AuditModeManager.isAuditing(uuid)) {
            AuditModeManager.enter(uuid);
            src.sendSuccess(() -> Component.literal("§3[CraftAudit] 已进入审计模式。"), false);
        }
        // 推荐只调用右键交互日志分页
        BlockInspectListener.queryInteractLogPage(player, page);
        return 1;
    }
}