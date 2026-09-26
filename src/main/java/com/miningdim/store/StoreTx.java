package com.miningdim.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 统一库的事务边界工具。
 *
 * 嵌套语义是本类存在的理由: 账本自身的每个写操作 (扣款 + 记账本条目) 必须原子, 但更外层还要能把
 * "扣钱 + 发资产"整体裹进一个事务。若内层无条件 commit, 外层的原子性就被内层提前落盘破坏。
 * 因此内层发现连接已处于事务中 (autoCommit 为 false) 时只执行, 不提交也不回滚 —— 提交权归最外层。
 *
 * 提交后队列 ({@link #afterCommit}) 是同一条嵌套语义的另一面: 内层登记的"提交之后才能做的事" (向别的模块广播
 * 已经落定的事实) 要等最外层真正提交后才执行, 最外层回滚时整批丢弃。内层自己的 call 返回时外层事务仍开着,
 * 在那一刻广播, 听众看到的是随时可能被回滚的中间态。
 *
 * 单写者前提: Minecraft 服务端逻辑是单线程的, 全服共用一条连接, 因此"当前是否在事务中"可以直接由
 * 连接状态判断, 不需要线程本地存储。提交后队列按连接分开记 (GameTest 会在独立的内存库上开事务)。
 */
public final class StoreTx {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/store");

    /** 各连接上等外层事务提交后执行的动作, 按登记顺序排列; 最外层 call 结束时 (提交或回滚) 整条取走。 */
    private static final Map<Connection, List<Runnable>> AFTER_COMMIT =
            Collections.synchronizedMap(new IdentityHashMap<>());

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
            // 回滚的事务里登记的提交后动作一律作废: 它们要广播的事实从未落盘。
            AFTER_COMMIT.remove(conn);
            rollback(conn, failure);
            setAutoCommit(conn, true);
            throw failure instanceof RuntimeException runtime
                    ? runtime
                    : new MiningStoreException("事务执行失败", failure);
        }
        // 先取走本事务的提交后动作再复原 autoCommit: 复原失败 (连接已坏) 时这批动作随之作废, 不会滞留到下一个事务里。
        List<Runnable> committed = AFTER_COMMIT.remove(conn);
        setAutoCommit(conn, true);
        if (committed != null) {
            committed.forEach(StoreTx::runQuietly);
        }
        return result;
    }

    /** 在事务中执行无返回值的动作。 */
    public static void run(Connection conn, Runnable body) {
        call(conn, () -> {
            body.run();
            return null;
        });
    }

    /**
     * 登记一个在当前事务提交之后才执行的动作。连接不在事务中 (刚才的写入已经自动提交) 时立即执行; 在事务中时排进
     * 该连接的提交后队列, 等本类开启的最外层事务提交、autoCommit 复原之后按登记顺序执行, 回滚则丢弃。
     *
     * <p>动作在事务之外执行, 可以自己再开事务。动作抛出的异常只记错误日志、不外抛, 也不影响排在后面的动作:
     * 走到这里时数据已经提交, 让异常冒出去只会把一次已经成功的写入报成失败。
     *
     * <p>只对经本类开启的事务有效。启动期的 schema 迁移与旧库导入自己管 autoCommit, 不要在其中登记。
     */
    public static void afterCommit(Connection conn, Runnable action) {
        Objects.requireNonNull(action, "action");
        if (!inTransaction(conn)) {
            runQuietly(action);
            return;
        }
        AFTER_COMMIT.computeIfAbsent(conn, ignored -> new ArrayList<>()).add(action);
    }

    private static void runQuietly(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            LOGGER.error("[miningdim] an after-commit action failed; the committed transaction is unaffected",
                    failure);
        }
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
