package cn.alini.craftaudit;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AuditModeManager {
    private static final Set<UUID> auditingPlayers = ConcurrentHashMap.newKeySet();

    public static void enter(UUID player) { auditingPlayers.add(player); }
    public static void exit(UUID player) { auditingPlayers.remove(player); }
    public static boolean isAuditing(UUID player) { return auditingPlayers.contains(player); }
}