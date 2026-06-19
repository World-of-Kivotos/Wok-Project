package com.miningdim.market.store;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 跳蚤市场 SQLite 连接管理 (契约第 2 节)。负责: 解析世界存档目录下的库文件路径 -> DriverManager 建连接
 * -> 开 WAL/外键 PRAGMA -> 返回持该连接的 {@link MarketDaoSqlite}; 以及 GameTest 用内存库与连接关闭。
 *
 * 连接生命周期 (契约第 2 节): MC 服务端逻辑单线程, 单写者契合 SQLite, 故服务端单连接; 由交易引擎子系统 (B)
 * 在 ServerStarting 开 / ServerStopping 关。本类只造与关连接, 不订阅事件 (生命周期编排归 B 的 Subsystem)。
 *
 * 驱动加载: 只 import {@code java.sql.*} + MC 的 server/storage (编译期不依赖 sqlite-jdbc jar, 严禁 import
 * org.sqlite.*)。运行期 org.xerial:sqlite-jdbc 上 game 层 classpath 由 build.gradle 提供: dev 经 runtimeOnly,
 * 生产经 jarJar 内嵌。必须显式 {@code Class.forName("org.sqlite.JDBC")} 注册 (见 {@link #ensureDriverRegistered}):
 * FML 模块化类加载下 DriverManager 的 JDBC4 ServiceLoader 自动注册在 JVM boot 层早期只跑一次, 那时 game 层的
 * sqlite jar 尚未加载, 不显式注册则 getConnection 抛 "No suitable driver found for jdbc:sqlite:"。
 */
public final class MarketDb {

    /** 库文件名 (契约第 2 节; 落每个世界存档独立目录)。 */
    public static final String DB_FILE_NAME = "miningdim_market.db";

    private MarketDb() {
    }

    /**
     * 服务端正式连接: 库文件落世界存档根目录 (契约第 2 节, 每存档独立), 开 WAL + 外键, 建表, 返回 DAO。
     * 由 B 在 ServerAboutToStart/ServerStarting 调用。
     *
     * @param server MC 服务端 (取世界路径)
     * @return 持已建表连接的 {@link MarketDaoSqlite}
     */
    public static MarketDaoSqlite open(MinecraftServer server) {
        Path dbPath = server.getWorldPath(LevelResource.ROOT).resolve(DB_FILE_NAME);
        // SQLite JDBC URL: 文件路径用正斜杠 (xerial 驱动接受平台原生路径, 但统一正斜杠避免 Windows 反斜杠转义歧义)。
        String url = "jdbc:sqlite:" + dbPath.toString().replace('\\', '/');
        return connectAndInit(url);
    }

    /**
     * GameTest 用内存库 (契约第 2 节: {@code jdbc:sqlite::memory:}, 走同一 DDL)。
     * 内存库随连接存活, close 即销毁, 测试间互不污染。
     */
    public static MarketDaoSqlite openInMemory() {
        return connectAndInit("jdbc:sqlite::memory:");
    }

    /** 关闭 DAO 持有的连接 (B 在 ServerStopping 调用)。已关/null 连接静默放过 (幂等关闭)。 */
    public static void close(MarketDaoSqlite dao) {
        if (dao == null) {
            return;
        }
        Connection conn = dao.connection();
        try {
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }
        } catch (SQLException e) {
            throw new MarketStoreException("MarketDb.close failed", e);
        }
    }

    private static MarketDaoSqlite connectAndInit(String url) {
        ensureDriverRegistered();
        Connection conn;
        try {
            conn = DriverManager.getConnection(url);
        } catch (SQLException e) {
            throw new MarketStoreException("MarketDb: failed to open SQLite connection at " + url, e);
        }
        applyPragmas(conn);
        MarketDaoSqlite dao = new MarketDaoSqlite(conn);
        dao.initSchema();
        return dao;
    }

    /**
     * 显式注册 SQLite JDBC 驱动 (FML 模块化类加载坑, 见类注释)。Class.forName 在 mod 代码 (game 层类加载器)
     * 触发 org.sqlite.JDBC 静态初始化向 DriverManager 注册; 注册后 getConnection 的 isDriverAllowed 校验
     * (caller=game 层) 通过。Class.forName 幂等 (类已加载则直接返回), 每次连接前调无额外开销。
     * 缺驱动是装配缺陷 (dev 漏 runtimeOnly / 生产漏 jarJar), 自然抛不静默。
     */
    private static void ensureDriverRegistered() {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new MarketStoreException(
                    "SQLite JDBC 驱动 org.sqlite.JDBC 不在运行期 classpath "
                            + "(dev 需 build.gradle runtimeOnly, 生产需 jarJar 内嵌)", e);
        }
    }

    /**
     * 连上后即开 WAL 与外键 (契约第 2 节)。WAL 提升单写多读并发并降锁竞争; foreign_keys=ON 启用外键约束
     * (SQLite 默认关)。内存库 (:memory:) 的 journal_mode 实际为 memory, PRAGMA 设置无害 (驱动忽略/无 WAL 文件)。
     */
    private static void applyPragmas(Connection conn) {
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA foreign_keys=ON");
        } catch (SQLException e) {
            throw new MarketStoreException("MarketDb: failed to apply WAL/foreign_keys pragmas", e);
        }
    }
}
