package cn.alini.craftaudit.event.entity;

import cn.alini.craftaudit.AuditModeManager;
import cn.alini.craftaudit.Craftaudit;
import cn.alini.craftaudit.storage.Database;
import cn.alini.craftaudit.storage.LogEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

@Mod.EventBusSubscriber(modid = Craftaudit.MODID)
public class EntityDeathListener {

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide()) return;


        DamageSource source = event.getSource();
        Entity trueSrc = source.getEntity();        // 归因实体（箭由射手归因）
        if (!(trueSrc instanceof ServerPlayer)) return; // 仅记录玩家击杀
        Entity directSrc = source.getDirectEntity(); // 直接实体（箭、火球等）

        // 维度与坐标：用受害者死亡位置
        String dim = victim.level().dimension().location().toString();
        BlockPos pos = victim.blockPosition();

        // 击杀者（写入 player 字段）
        String actorName;
        ServerPlayer killerPlayer = null;
        if (trueSrc instanceof ServerPlayer sp) {
            killerPlayer = sp;
            // 审计模式中的玩家不落库（与现有策略一致）
            if (AuditModeManager.isAuditing(sp.getUUID())) return;
            actorName = sp.getName().getString();
        } else if (trueSrc != null) {
            actorName = ForgeRegistries.ENTITY_TYPES.getKey(trueSrc.getType()).toString();
        } else {
            actorName = "(环境)";
        }


        // 受害者（写入 target 字段）
        String target;
        if (victim instanceof ServerPlayer vp) {
            target = "player:" + vp.getName().getString();
        } else {
            target = ForgeRegistries.ENTITY_TYPES.getKey(victim.getType()).toString();
        }

        // 附加信息（写入 data 字段，JSON）
        String causeId = getCauseId(source);
        String projectileId = (directSrc != null && directSrc != trueSrc)
                ? ForgeRegistries.ENTITY_TYPES.getKey(directSrc.getType()).toString()
                : null;
        String weaponId = null;
        if (killerPlayer != null) {
            ItemStack main = killerPlayer.getMainHandItem();
            if (!main.isEmpty() && ForgeRegistries.ITEMS.getKey(main.getItem()) != null) {
                weaponId = ForgeRegistries.ITEMS.getKey(main.getItem()).toString();
            }
        }
        Double distance = null;
        if (killerPlayer != null) {
            distance = (double)Math.round(killerPlayer.distanceTo(victim) * 10.0) / 10.0; // 保留1位小数
        }

        String dataJson = buildKillDataJson(causeId, projectileId, weaponId, distance);

        // 写库（action = kill）
        Database.get().insertAsync(new LogEntry(
                System.currentTimeMillis(),
                dim,
                pos.getX(), pos.getY(), pos.getZ(),
                actorName,
                "kill",
                target,
                dataJson
        ));
    }

    private static String getCauseId(DamageSource src) {
        try {
            // 1.19+ 有 getMsgId()
            return src.getMsgId();
        } catch (Throwable ignore) {
            return "unknown";
        }
    }

    private static String buildKillDataJson(String cause, String projectile, String weapon, Double distance) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        if (cause != null) {
            sb.append("\"cause\":\"").append(escape(cause)).append("\"");
            first = false;
        }
        if (projectile != null) {
            if (!first) sb.append(",");
            sb.append("\"projectile\":\"").append(escape(projectile)).append("\"");
            first = false;
        }
        if (weapon != null) {
            if (!first) sb.append(",");
            sb.append("\"weapon\":\"").append(escape(weapon)).append("\"");
            first = false;
        }
        if (distance != null) {
            if (!first) sb.append(",");
            sb.append("\"distance\":").append(distance);
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}