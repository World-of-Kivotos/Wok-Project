package com.miningdim.store;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.sql.Connection;

/**
 * 全服唯一 SQLite 连接的持有者与服务定位器。
 *
 * 与 {@code MarketServices} / {@code CaseServices} 同一范式: 静态持有、启动期注入、取用时未就绪即抛,
 * 严禁返回 null 掩盖装配缺陷。区别在于本类持有的是跨子系统共享的资源 —— 市场与开箱各自的 DAO 都建在这
 * 同一条连接上, 这样它们的写入才可能落进同一个事务 (这正是合库的目的, 两个库各自开事务并不构成原子性)。
 */
public final class MiningStore {

    private MiningStore() {
    }

    private static Connection connection;

    /**
     * 打开统一库: 建连接 -> 推进 schema -> 导入遗留的独立库。任一步失败都关掉半开的连接再冒泡,
     * 让服务端启动失败而不是带着半成品的库继续跑。
     */
    public static void open(MinecraftServer server) {
        if (connection != null) {
            throw new MiningStoreException("MiningStore 已打开; 上一个世界的连接未在停服时释放");
        }
        Connection conn = MiningDb.open(server);
        try {
            MiningSchema.apply(conn);
            LegacyStoreImport.importLegacyDatabases(conn,
                    server.getWorldPath(LevelResource.ROOT), System.currentTimeMillis());
            // V3 的结算回填只在 apply() 那一刻跑一次, 看不到刚导入的旧库 COMMITTED 行 (其付款证据只在
            // 已删除的旧版 SavedData 里, bundle_operations 永远查无此笔); 不补跑这一次, 这些行会被永久
            // 当成硬崩溃孤儿, 30 天保留期后遭真实重复扣款 (见 MiningSchema.backfillCaseEconomySettled 的
            // javadoc)。
            MiningSchema.backfillCaseEconomySettled(conn);
        } catch (RuntimeException failure) {
            MiningDb.close(conn);
            throw failure;
        }
        connection = conn;
    }

    /** 取统一连接; 未打开抛 IllegalStateException (装配顺序错误, 不是可恢复的运行期状态)。 */
    public static Connection connection() {
        if (connection == null) {
            throw new IllegalStateException(
                    "MiningStore 尚未打开 (由 MiningStoreSubsystem 在 ServerAboutToStartEvent 打开)");
        }
        return connection;
    }

    /** 是否已打开。 */
    public static boolean isOpen() {
        return connection != null;
    }

    /**
     * 关闭并清引用。先清字段再关连接: 关闭本身抛错时字段也必须已经清干净, 否则下一个世界会因为
     * "已打开"而拒绝启动, 把一次关闭失败放大成再也起不来。
     */
    public static void close() {
        Connection conn = connection;
        connection = null;
        MiningDb.close(conn);
    }
}
