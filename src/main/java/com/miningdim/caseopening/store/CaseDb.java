package com.miningdim.caseopening.store;

import com.miningdim.store.MiningDb;
import com.miningdim.store.MiningSchema;

import java.sql.Connection;

/**
 * 开箱账本 DAO 的构造入口。
 *
 * 合库后开箱表并入统一库 {@code miningdim.db}, 本类不再拥有连接与建表 (见 {@link MiningSchema})。
 * 开箱的"扣钱 + 发皮肤"要成为单个事务, 钱与皮肤就必须在同一个库、同一条连接上。
 */
public final class CaseDb {

    private CaseDb() {
    }

    /** 在统一库连接上构造 DAO (服务端正式路径; 连接的开关不归本类)。 */
    public static CaseDaoSqlite on(Connection connection) {
        return new CaseDaoSqlite(connection);
    }

    /** GameTest 专用: 开一条独立的内存统一库并建好全部表。 */
    public static CaseDaoSqlite openInMemory() {
        Connection connection = MiningDb.openInMemory();
        MiningSchema.apply(connection);
        return new CaseDaoSqlite(connection);
    }

    /**
     * GameTest 专用: 关掉 {@link #openInMemory()} 造出来的连接。
     * 严禁在服务端路径调用 —— 那条连接是全服共享的, 由存储子系统在停服时统一关闭。
     */
    public static void close(CaseDaoSqlite dao) {
        if (dao == null) {
            return;
        }
        MiningDb.close(dao.connection());
    }
}
