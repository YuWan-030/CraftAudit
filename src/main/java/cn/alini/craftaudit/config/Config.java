// java
package cn.alini.craftaudit.config;

import cn.alini.craftaudit.Craftaudit;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.commons.lang3.tuple.Pair;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Config {
    public static final ForgeConfigSpec COMMON_SPEC;
    public static final Common COMMON;

    static {
        Pair<Common, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(Common::new);
        COMMON = pair.getLeft();
        COMMON_SPEC = pair.getRight();
    }

    public enum DbMode { SQLITE, MYSQL }

    public static boolean isSqlite() { return COMMON.dbMode.get() == DbMode.SQLITE; }
    public static boolean isMySql() { return COMMON.dbMode.get() == DbMode.MYSQL; }

    // SQLite: 解析到游戏目录
    public static Path sqliteDbPath() {
        // 获取游戏根目录
        Path baseDir = FMLPaths.GAMEDIR.get();
        // 构造 modid 文件夹路径
        Path modDir = baseDir.resolve(Craftaudit.MODID);
        // 自动创建 modid 文件夹
        try {
            Files.createDirectories(modDir);
        } catch (IOException e) {
            e.printStackTrace();
        }
        // 数据库文件路径（如 craftaudit.db）
        return modDir.resolve(COMMON.sqlitePath.get());
    }

    // MySQL: 生成 JDBC URL
    public static String mysqlJdbcUrl() {
        String host = COMMON.mysqlHost.get();
        int port = COMMON.mysqlPort.get();
        String db = COMMON.mysqlDatabase.get();
        boolean ssl = COMMON.mysqlUseSSL.get();
        String params = COMMON.mysqlParams.get();

        String base = "jdbc:mysql://" + host + ":" + port + "/" + db
                + "?useSSL=" + ssl
                + "&characterEncoding=utf8"
                + "&serverTimezone=UTC"
                + "&rewriteBatchedStatements=true";
        if (params != null && !params.isBlank()) {
            return base + "&" + params;
        }
        return base;
    }

    public static String mysqlUser() { return COMMON.mysqlUser.get(); }
    public static String mysqlPassword() { return COMMON.mysqlPassword.get(); }

    public static final class Common {
        public final ForgeConfigSpec.EnumValue<DbMode> dbMode;

        // SQLite
        public final ForgeConfigSpec.ConfigValue<String> sqlitePath;

        // MySQL
        public final ForgeConfigSpec.ConfigValue<String> mysqlHost;
        public final ForgeConfigSpec.IntValue mysqlPort;
        public final ForgeConfigSpec.ConfigValue<String> mysqlDatabase;
        public final ForgeConfigSpec.ConfigValue<String> mysqlUser;
        public final ForgeConfigSpec.ConfigValue<String> mysqlPassword;
        public final ForgeConfigSpec.BooleanValue mysqlUseSSL;
        public final ForgeConfigSpec.ConfigValue<String> mysqlParams;

        private Common(ForgeConfigSpec.Builder builder) {
            builder.push("database");

            dbMode = builder
                    .comment("数据库模式: SQLITE 或 MYSQL")
                    .defineEnum("mode", DbMode.SQLITE);

            builder.push("sqlite");
            sqlitePath = builder
                    .comment("SQLite 文件名（存于游戏根目录下的craftaudit文件夹）")
                    .define("path", "craftaudit.db");
            builder.pop();

            builder.push("mysql");
            mysqlHost = builder.define("host", "127.0.0.1");
            mysqlPort = builder.defineInRange("port", 3306, 1, 65535);
            mysqlDatabase = builder.define("database", "craftaudit");
            mysqlUser = builder.define("user", "root");
            mysqlPassword = builder.comment("注意: 明文存储，请谨慎使用公共环境").define("password", "");
            mysqlUseSSL = builder.define("useSSL", false);
            mysqlParams = builder.comment("附加 JDBC 参数，形如 `allowPublicKeyRetrieval=true`").define("params", "");
            builder.pop();

            builder.pop();
        }
    }
}
