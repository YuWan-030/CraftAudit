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
            // 自动创建数据库目录
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

    public static Database get() {
        return INSTANCE;
    }

    public static boolean isConnected() {
        return INSTANCE != null;
    }

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
        try {
            st.execute(sql);
        } catch (SQLException ignored) { }
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

    /**
     * 分页查询指定位置的日志
     * @param dimension 维度
     * @param x 坐标
     * @param y 坐标
     * @param z 坐标
     * @param actionPattern 形如 "break|place", "put|take"
     * @param page 页码，从1开始
     * @param pageSize 每页条数
     * @return 日志列表
     */
    public List<LogEntry> queryLogsAtPaged(String dimension, int x, int y, int z, String actionPattern, int page, int pageSize) {
        List<LogEntry> result = new ArrayList<>();
        int offset = (page - 1) * pageSize;
        String[] actions = actionPattern.split("\\|");

        // 构造占位符 (?, ?, ...)
        StringBuilder actionsPlaceholder = new StringBuilder();
        for (int i = 0; i < actions.length; i++) {
            if (i > 0) actionsPlaceholder.append(",");
            actionsPlaceholder.append("?");
        }
        String sql = "SELECT time_ms, dimension, x, y, z, player, action, target, data FROM logs WHERE dimension=? AND x=? AND y=? AND z=? AND action IN (" + actionsPlaceholder + ") ORDER BY time_ms DESC LIMIT ? OFFSET ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, dimension);
            ps.setInt(2, x);
            ps.setInt(3, y);
            ps.setInt(4, z);
            // 设置 action 参数
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

    /**
     * 查询指定位置的总日志数（用于分页显示总页数）
     * @param dimension 维度
     * @param x 坐标
     * @param y 坐标
     * @param z 坐标
     * @param actionPattern 支持 "break|place", "put|take"
     * @return 该位置的日志总数
     */
    public int countLogsAt(String dimension, int x, int y, int z, String actionPattern) {
        String sql = "SELECT COUNT(*) FROM logs WHERE dimension=? AND x=? AND y=? AND z=? AND (action=? OR action=?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, dimension);
            ps.setInt(2, x);
            ps.setInt(3, y);
            ps.setInt(4, z);
            String[] actions = actionPattern.split("\\|");
            ps.setString(5, actions[0]);
            ps.setString(6, actions.length > 1 ? actions[1] : actions[0]);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            LOGGER.error("统计日志失败", e);
        }
        return 0;
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