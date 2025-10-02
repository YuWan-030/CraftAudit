package cn.alini.craftaudit.storage;

public record LogEntry(
        long timeMillis,
        String dimension,
        int x, int y, int z,
        String player,
        String playerUuid, // 新增：玩家UUID；环境事件可为 null
        String action,
        String target,
        String data // 对于容器变更，存json：{"type":"take","item":"apple","count":3}
) {}