package com.miningdim.store;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Supplier;

/**
 * 统一库的事务边界工具。
 *
 * 嵌套语义是本类存在的理由: 账本自身的每个写操作 (扣款 + 记账本条目) 必须原子, 但更外层还要能把
 * "扣钱 + 发资产"整体裹进一个事务。若内层无条件 commit, 外层的原子性就被内层提前落盘破坏。
 * 因此内层发现连接已处于事务中 (autoCommit 为 false) 时只执行, 不提交也不回滚 —— 提交权归最外层。
 *
 * 单写者前提: Minecraft 服务端逻辑是单线程的, 全服共用一条连接, 因此"当前是否在事务中"可以直接由
 * 连接状态判断, 不需要线程本地存储。
 */
public final class StoreTx {

    private StoreTx() {
    }

    /** 在事务中执行并返回结果; 已在外层事务中时直接执行。 */
    public static <T> T call(Connection conn, Supplier<T> body) {
        if (inTransaction(conn)) {
            return body.get();
        }
        setAutoCommit(conn, false);
        T result;
        try {
            result = body.get();
            conn.commit();
        } catch (RuntimeException | SQLException failure) {
            rollback(conn, failure);
            setAutoCommit(conn, true);
            throw failure instanceof RuntimeException runtime
                    ? runtime
                    : new MiningStoreException("事务执行失败", failure);
        }
        setAutoCommit(conn, true);
        return result;
    }

    /** 在事务中执行无返回值的动作。 */
    public static void run(Connection conn, Runnable body) {
        call(conn, () -> {
            body.run();
            return null;
        });
    }

    private static boolean inTransaction(Connection conn) {
        try {
            return !conn.getAutoCommit();
        } catch (SQLException e) {
            throw new MiningStoreException("读取 autoCommit 失败", e);
        }
    }

    private static void setAutoCommit(Connection conn, boolean value) {
        try {
            conn.setAutoCommit(value);
        } catch (SQLException e) {
            throw new MiningStoreException("切换 autoCommit 失败", e);
        }
    }

    private static void rollback(Connection conn, Throwable primary) {
        try {
            conn.rollback();
        } catch (SQLException rollbackFailure) {
            // 回滚失败会掩盖真正的业务错误, 挂 suppressed 上一并暴露。
            primary.addSuppressed(rollbackFailure);
        }
    }
}
