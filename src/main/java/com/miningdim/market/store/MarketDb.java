package com.miningdim.market.store;

import com.miningdim.store.MiningDb;
import com.miningdim.store.MiningSchema;

import java.sql.Connection;

/**
 * 跳蚤市场 DAO 的构造入口。
 *
 * 合库后本类不再拥有连接与建表: 库文件、PRAGMA 与 schema 版本统一归 {@link com.miningdim.store.MiningStore}
 * 与 {@link MiningSchema} 管, 市场表只是统一库 {@code miningdim.db} 里的一组表。这是"扣钱与发资产同事务"的
 * 前提 —— 市场若仍独占一个库文件, 它的事务与钱的事务就永远不可能是同一个。
 */
public final class MarketDb {

    private MarketDb() {
    }

    /** 在统一库连接上构造 DAO (服务端正式路径; 连接的开关不归本类)。 */
    public static MarketDaoSqlite on(Connection conn) {
        return new MarketDaoSqlite(conn);
    }

    /**
     * GameTest 专用: 开一条独立的内存统一库并建好全部表。
     * 内存库随连接存活, 用例之间互不污染。
     */
    public static MarketDaoSqlite openInMemory() {
        Connection conn = MiningDb.openInMemory();
        MiningSchema.apply(conn);
        return new MarketDaoSqlite(conn);
    }

    /**
     * GameTest 专用: 关掉 {@link #openInMemory()} 造出来的连接。
     * 严禁在服务端路径调用 —— 那条连接是全服共享的, 由存储子系统在停服时统一关闭。
     */
    public static void close(MarketDaoSqlite dao) {
        if (dao == null) {
            return;
        }
        MiningDb.close(dao.connection());
    }
}
