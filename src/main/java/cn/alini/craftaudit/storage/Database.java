package cn.alini.craftaudit.storage;

import cn.alini.craftaudit.config.Config;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.IOException;
import java.nio.file.Files;

public final class Database {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static Database INSTANCE;

    private enum Dialect { SQLITE, MYSQL }

    private final Connection conn;
    private final Dialect dialect;

    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "craftaudit-sql-writer");
        t.setDaemon(true);
        return t;
    });

    private Database(Connection conn, Dialect dialect) throws SQLException {
        this.conn = conn;
        this.dialect = dialect;
        ensureSchema();
    }

    public static synchronized void init(Path sqliteDefaultPath) throws SQLException {
        if (INSTANCE != null) return;

        if (Config.isSqlite()) {
            Path dbPath = Config.sqliteDbPath() != null ? Config.sqliteDbPath() : sqliteDefaultPath;
            try {
                Files.createDirectories(dbPath.getParent());
            } catch (IOException e) {
                LOGGER.error("创建数据库目录失败", e);
            }
            String url = "jdbc:sqlite:" + dbPath;
            LOGGER.info("[craftaudit] 使用 SQLite: {}", dbPath);
            Connection c = DriverManager.getConnection(url);
            INSTANCE = new Database(c, Dialect.SQLITE);
        } else {
            String url = Config.mysqlJdbcUrl();
            String user = Config.mysqlUser();
            String pass = Config.mysqlPassword();
            LOGGER.info("[craftaudit] 使用 MySQL: {}", url);
            Connection c = DriverManager.getConnection(url, user, pass);
            INSTANCE = new Database(c, Dialect.MYSQL);
        }
    }

    public static Database get() { return INSTANCE; }
    public static boolean isConnected() { return INSTANCE != null; }

    public boolean ping() {
        try (Statement st = conn.createStatement()) {
            st.execute("SELECT 1");
            return true;
        } catch (SQLException e) {
            LOGGER.warn("[craftaudit] 数据库 ping 失败", e);
            return false;
        }
    }

    private void ensureSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            if (dialect == Dialect.SQLITE) {
                st.execute("""
                    CREATE TABLE IF NOT EXISTS logs(
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      time_ms INTEGER NOT NULL,
                      dimension TEXT NOT NULL,
                      x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,
                      player TEXT,
                      action TEXT NOT NULL,
                      target TEXT,
                      data TEXT
                    )
                """);
                st.execute("CREATE INDEX IF NOT EXISTS idx_logs_pos ON logs(dimension, x, y, z)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_logs_time ON logs(time_ms)");
            } else {
                st.execute("""
                    CREATE TABLE IF NOT EXISTS logs(
                      id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                      time_ms BIGINT NOT NULL,
                      dimension VARCHAR(128) NOT NULL,
                      x INT NOT NULL, y INT NOT NULL, z INT NOT NULL,
                      player VARCHAR(64),
                      action VARCHAR(32) NOT NULL,
                      target VARCHAR(191),
                      data TEXT
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
                tryExec(st, "CREATE INDEX idx_logs_pos ON logs(dimension, x, y, z)");
                tryExec(st, "CREATE INDEX idx_logs_time ON logs(time_ms)");
            }
        }
    }

    private static void tryExec(Statement st, String sql) {
        try { st.execute(sql); } catch (SQLException ignored) { }
    }

    public void insertAsync(LogEntry e) {
        writer.execute(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO logs(time_ms, dimension, x, y, z, player, action, target, data) VALUES (?,?,?,?,?,?,?,?,?)")) {
                ps.setLong(1, e.timeMillis());
                ps.setString(2, e.dimension());
                ps.setInt(3, e.x());
                ps.setInt(4, e.y());
                ps.setInt(5, e.z());
                ps.setString(6, e.player());
                ps.setString(7, e.action());
                ps.setString(8, e.target());
                ps.setString(9, e.data());
                ps.executeUpdate();
            } catch (SQLException ex) {
                LOGGER.error("写入日志失败", ex);
            }
        });
    }

    public List<LogEntry> queryLogsAtPaged(String dimension, int x, int y, int z, String actionPattern, int page, int pageSize) {
        List<LogEntry> result = new ArrayList<>();
        int offset = (page - 1) * pageSize;
        String[] actions = actionPattern.split("\\|");

        StringBuilder actionsPlaceholder = new StringBuilder();
        for (int i = 0; i < actions.length; i++) {
            if (i > 0) actionsPlaceholder.append(",");
            actionsPlaceholder.append("?");
        }
        String sql = "SELECT time_ms, dimension, x, y, z, player, action, target, data " +
                "FROM logs WHERE dimension=? AND x=? AND y=? AND z=? AND action IN (" + actionsPlaceholder + ") " +
                "ORDER BY time_ms DESC LIMIT ? OFFSET ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, dimension);
            ps.setInt(2, x);
            ps.setInt(3, y);
            ps.setInt(4, z);
            for (int i = 0; i < actions.length; i++) {
                ps.setString(5 + i, actions[i]);
            }
            ps.setInt(5 + actions.length, pageSize);
            ps.setInt(6 + actions.length, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new LogEntry(
                            rs.getLong("time_ms"),
                            rs.getString("dimension"),
                            rs.getInt("x"),
                            rs.getInt("y"),
                            rs.getInt("z"),
                            rs.getString("player"),
                            rs.getString("action"),
                            rs.getString("target"),
                            rs.getString("data")
                    ));
                }
            }
        } catch (SQLException e) {
            LOGGER.error("查询日志失败", e);
        }
        return result;
    }

    public int countLogsAt(String dimension, int x, int y, int z, String actionPattern) {
        String[] actions = actionPattern.split("\\|");
        StringBuilder actionsPlaceholder = new StringBuilder();
        for (int i = 0; i < actions.length; i++) {
            if (i > 0) actionsPlaceholder.append(",");
            actionsPlaceholder.append("?");
        }
        String sql = "SELECT COUNT(*) FROM logs WHERE dimension=? AND x=? AND y=? AND z=? AND action IN (" + actionsPlaceholder + ")";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, dimension);
            ps.setInt(2, x);
            ps.setInt(3, y);
            ps.setInt(4, z);
            for (int i = 0; i < actions.length; i++) {
                ps.setString(5 + i, actions[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            LOGGER.error("统计日志失败", e);
        }
        return 0;
    }

    // 半径 + 时间范围查询
    public List<LogEntry> queryLogsNearPaged(String dimension, int cx, int cy, int cz, int radius, long sinceMs, int page, int pageSize) {
        List<LogEntry> result = new ArrayList<>();
        int offset = (page - 1) * pageSize;

        int minX = cx - radius, maxX = cx + radius;
        int minY = cy - radius, maxY = cy + radius;
        int minZ = cz - radius, maxZ = cz + radius;

        String sql = "SELECT time_ms, dimension, x, y, z, player, action, target, data " +
                "FROM logs WHERE dimension=? AND time_ms>=? " +
                "AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ? " +
                "ORDER BY time_ms DESC LIMIT ? OFFSET ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, dimension);
            ps.setLong(2, sinceMs);
            ps.setInt(3, minX); ps.setInt(4, maxX);
            ps.setInt(5, minY); ps.setInt(6, maxY);
            ps.setInt(7, minZ); ps.setInt(8, maxZ);
            ps.setInt(9, pageSize);
            ps.setInt(10, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new LogEntry(
                            rs.getLong("time_ms"),
                            rs.getString("dimension"),
                            rs.getInt("x"),
                            rs.getInt("y"),
                            rs.getInt("z"),
                            rs.getString("player"),
                            rs.getString("action"),
                            rs.getString("target"),
                            rs.getString("data")
                    ));
                }
            }
        } catch (SQLException e) {
            LOGGER.error("半径查询失败", e);
        }
        return result;
    }

    public int countLogsNear(String dimension, int cx, int cy, int cz, int radius, long sinceMs) {
        int minX = cx - radius, maxX = cx + radius;
        int minY = cy - radius, maxY = cy + radius;
        int minZ = cz - radius, maxZ = cz + radius;

        String sql = "SELECT COUNT(*) FROM logs WHERE dimension=? AND time_ms>=? " +
                "AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, dimension);
            ps.setLong(2, sinceMs);
            ps.setInt(3, minX); ps.setInt(4, maxX);
            ps.setInt(5, minY); ps.setInt(6, maxY);
            ps.setInt(7, minZ); ps.setInt(8, maxZ);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            LOGGER.error("统计半径查询失败", e);
        }
        return 0;
    }

    // 区域 + 时间（可按玩家过滤）
    public List<LogEntry> queryLogsRegionSince(String dimension,
                                               int minX, int maxX,
                                               int minY, int maxY,
                                               int minZ, int maxZ,
                                               long sinceMs,
                                               List<String> actions,
                                               String player /* 可为 null */) {
        List<LogEntry> result = new ArrayList<>();
        if (actions == null || actions.isEmpty()) return result;

        StringBuilder in = new StringBuilder();
        for (int i = 0; i < actions.size(); i++) {
            if (i > 0) in.append(",");
            in.append("?");
        }
        String sql = "SELECT time_ms, dimension, x, y, z, player, action, target, data " +
                "FROM logs WHERE dimension=? AND time_ms>=? " +
                "AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ? " +
                "AND action IN (" + in + ") ";
        if (player != null) sql += "AND player=? ";
        sql += "ORDER BY time_ms DESC";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            ps.setString(idx++, dimension);
            ps.setLong(idx++, sinceMs);
            ps.setInt(idx++, minX); ps.setInt(idx++, maxX);
            ps.setInt(idx++, minY); ps.setInt(idx++, maxY);
            ps.setInt(idx++, minZ); ps.setInt(idx++, maxZ);
            for (String a : actions) ps.setString(idx++, a);
            if (player != null) ps.setString(idx++, player);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new LogEntry(
                            rs.getLong("time_ms"),
                            rs.getString("dimension"),
                            rs.getInt("x"),
                            rs.getInt("y"),
                            rs.getInt("z"),
                            rs.getString("player"),
                            rs.getString("action"),
                            rs.getString("target"),
                            rs.getString("data")
                    ));
                }
            }
        } catch (SQLException e) {
            LOGGER.error("区域时间查询失败", e);
        }
        return result;
    }

    // 新增：清理早于某时间的日志（返回删除条数；失败返回 -1）
    public int deleteLogsBefore(long beforeMs) {
        String sql = "DELETE FROM logs WHERE time_ms < ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, beforeMs);
            int affected = ps.executeUpdate();

            // 可选：SQLite 做一次 VACUUM 回收空间（非必要，可能耗时）
            if (affected > 0 && dialect == Dialect.SQLITE) {
                try (Statement st = conn.createStatement()) {
                    st.execute("VACUUM");
                } catch (SQLException e) {
                    LOGGER.warn("SQLite VACUUM 失败：{}", e.toString());
                }
            }
            return affected;
        } catch (SQLException e) {
            LOGGER.error("清理日志失败", e);
            return -1;
        }
    }

    public void close() {
        writer.shutdown();
        try {
            conn.close();
            LOGGER.info("数据库连接已关闭");
        } catch (SQLException e) {
            LOGGER.error("关闭数据库连接失败", e);
        }
    }
}