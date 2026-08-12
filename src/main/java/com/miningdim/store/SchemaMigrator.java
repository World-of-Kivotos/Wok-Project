package com.miningdim.store;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * 基于 {@code PRAGMA user_version} 的 schema 迁移器。
 *
 * 为什么必须有: 此前两个库都靠 {@code CREATE TABLE IF NOT EXISTS} 建表, 那对【已存在的表】是彻底的
 * no-op —— 给现存表加一列不会发生, 且没有任何地方记录当前 schema 到了哪一版。只要库一旦落到玩家的
 * 存档里, 后续任何结构变更都无从下手。
 *
 * 语义: user_version 记录已应用的迁移数量。第 i 个迁移 (从 1 开始编号) 只在 user_version < i 时执行,
 * 执行完把 user_version 置为 i。全部迁移在单个事务内完成, 任一步失败整体回滚, 不会留下半迁移的库。
 *
 * 铁律: 已发布的迁移脚本严禁修改或删除, 只能在末尾追加新脚本。改动既有脚本会让已升级的存档与新存档
 * 产生结构分歧, 而 user_version 却显示同一版本, 这类不一致事后无法诊断。
 */
public final class SchemaMigrator {

    private SchemaMigrator() {
    }

    /**
     * 把库结构推进到 migrations 描述的最新版本。
     *
     * @param migrations 按版本顺序排列的迁移; 每个元素是该版本要执行的全部 DDL 语句
     */
    public static void migrate(Connection conn, List<List<String>> migrations) {
        int current = userVersion(conn);
        if (current > migrations.size()) {
            // 库比代码新: 多半是回滚了 mod 版本却保留了存档。继续跑会用旧代码写新结构, 必须拒绝启动。
            throw new MiningStoreException("数据库 schema 版本 " + current
                    + " 高于本版 mod 支持的 " + migrations.size() + "; 请勿用旧版 mod 打开已升级的存档");
        }
        if (current == migrations.size()) {
            return;
        }
        boolean autoCommit = setAutoCommit(conn, false);
        try {
            for (int version = current + 1; version <= migrations.size(); version++) {
                for (String ddl : migrations.get(version - 1)) {
                    exec(conn, ddl);
                }
                // user_version 不接受参数绑定, 只能拼接; version 是代码内生成的循环下标, 不来自外部输入。
                exec(conn, "PRAGMA user_version=" + version);
            }
            conn.commit();
        } catch (RuntimeException | SQLException failure) {
            rollback(conn, failure);
            throw failure instanceof RuntimeException runtime
                    ? runtime
                    : new MiningStoreException("schema 迁移失败", failure);
        } finally {
            setAutoCommit(conn, autoCommit);
        }
    }

    public static int userVersion(Connection conn) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new MiningStoreException("读取 user_version 失败", e);
        }
    }

    /** 表是否存在; 供从旧库导入数据前探测。 */
    public static boolean tableExists(Connection conn, String tableName) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT 1 FROM sqlite_master WHERE type='table' AND name='" + tableName + "'")) {
            return rs.next();
        } catch (SQLException e) {
            throw new MiningStoreException("探测表是否存在失败: " + tableName, e);
        }
    }

    private static void exec(Connection conn, String sql) {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new MiningStoreException("执行 schema 语句失败: " + sql, e);
        }
    }

    private static boolean setAutoCommit(Connection conn, boolean value) {
        try {
            boolean previous = conn.getAutoCommit();
            conn.setAutoCommit(value);
            return previous;
        } catch (SQLException e) {
            throw new MiningStoreException("切换 autoCommit 失败", e);
        }
    }

    private static void rollback(Connection conn, Throwable primary) {
        try {
            conn.rollback();
        } catch (SQLException rollbackFailure) {
            // 回滚本身失败会掩盖真正的迁移错误, 挂到 suppressed 上一并暴露, 不吞掉任何一个。
            primary.addSuppressed(rollbackFailure);
        }
    }
}
