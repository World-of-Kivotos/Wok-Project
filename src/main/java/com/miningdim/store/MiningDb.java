package com.miningdim.store;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 全服唯一的 SQLite 连接与库文件管理。
 *
 * 存在的理由是事务边界: 跳蚤市场与开箱此前各持一个独立库文件, 而钱在 Minecraft SavedData。同一笔不可
 * 分割的经济不变量跨越多个存储且没有共同事务, 崩溃后必然出现"资产在、钱回滚了"这类无法自愈的状态。
 * 把全部经济事实收进单一库文件, 是让 BEGIN/COMMIT 真正覆盖"扣钱 + 发资产"的前提 —— 分散在两个库里
 * 各自开事务并不构成原子性。
 *
 * 单连接单写者: Minecraft 服务端逻辑是单线程的, 因此不设连接池。所有经济写入必须在服务端主线程完成。
 */
public final class MiningDb {

    /** 统一库文件名; 落每个世界存档独立目录。 */
    public static final String DB_FILE_NAME = "miningdim.db";

    private MiningDb() {
    }

    /** 服务端正式连接: 库文件落世界存档根目录, 应用 PRAGMA 后交给迁移器补齐 schema。 */
    public static Connection open(MinecraftServer server) {
        Path dbPath = server.getWorldPath(LevelResource.ROOT).resolve(DB_FILE_NAME);
        // 统一正斜杠: xerial 驱动接受平台原生路径, 但 Windows 反斜杠在 JDBC URL 里有转义歧义。
        return connect("jdbc:sqlite:" + dbPath.toString().replace('\\', '/'));
    }

    /** GameTest 用内存库, 走同一套 PRAGMA 与迁移。 */
    public static Connection openInMemory() {
        return connect("jdbc:sqlite::memory:");
    }

    /** 指定文件路径连接; 供需要验证"关闭重开后数据仍在"的崩溃恢复测试使用。 */
    public static Connection openAt(Path dbPath) {
        return connect("jdbc:sqlite:" + dbPath.toString().replace('\\', '/'));
    }

    private static Connection connect(String url) {
        ensureDriverRegistered();
        Connection conn;
        try {
            conn = DriverManager.getConnection(url);
        } catch (SQLException e) {
            throw new MiningStoreException("MiningDb: 无法打开 SQLite 连接 " + url, e);
        }
        applyPragmas(conn);
        return conn;
    }

    /**
     * 显式注册 SQLite JDBC 驱动。
     *
     * FML 模块化类加载下 DriverManager 的 JDBC4 ServiceLoader 自动注册只在 JVM boot 层早期跑一次, 那时
     * game 层的 sqlite jar 尚未加载; 不显式注册则 getConnection 抛 "No suitable driver found"。
     * 缺驱动属装配缺陷 (dev 漏 minecraftLibrary / 生产漏 jarJar), 自然抛出不静默。
     */
    private static void ensureDriverRegistered() {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new MiningStoreException(
                    "SQLite JDBC 驱动 org.sqlite.JDBC 不在运行期 classpath "
                            + "(dev 需 build.gradle minecraftLibrary, 生产需 jarJar 内嵌)", e);
        }
    }

    private static void applyPragmas(Connection conn) {
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            // synchronous=NORMAL 是本方案性能可行的前提。WAL 下该档仍保证【进程崩溃】后已提交事务不丢,
            // 只有操作系统崩溃或掉电才可能丢最近几笔; 而本项目要防的故障模型正是服务端进程崩溃。
            // 若沿用默认 FULL, 挖矿连锁一 tick 内数十次 faucet 入账会变成数十次 fsync, 直接卡死主线程。
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA busy_timeout=5000");
        } catch (SQLException e) {
            throw new MiningStoreException("MiningDb: 应用 PRAGMA 失败", e);
        }
    }

    /** 幂等关闭; 已关或 null 静默放过。 */
    public static void close(Connection conn) {
        if (conn == null) {
            return;
        }
        try {
            if (!conn.isClosed()) {
                conn.close();
            }
        } catch (SQLException e) {
            throw new MiningStoreException("MiningDb: 关闭连接失败", e);
        }
    }
}
