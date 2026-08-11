package com.miningdim.store;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * 统一存储层与 schema 迁移设施的 GameTest。
 *
 * 迁移器是后续全部经济数据入库的地基, 它自身出错会静默损坏存档结构, 因此这里断言的都是具体结果
 * (确切的 user_version 数值、确切的行数、确切的列值), 不用"不抛异常"之类的弱校验。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class MiningStoreGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "store";

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void migrationsApplyInOrderAndAreIdempotent(GameTestHelper helper) {
        Connection conn = MiningDb.openInMemory();
        try {
            helper.assertTrue(SchemaMigrator.userVersion(conn) == 0, "全新库的 user_version 必须是 0");

            List<List<String>> migrations = List.of(
                    List.of("CREATE TABLE t_one (id INTEGER PRIMARY KEY, name TEXT NOT NULL)"),
                    List.of("CREATE TABLE t_two (id INTEGER PRIMARY KEY)",
                            "ALTER TABLE t_one ADD COLUMN note TEXT"));

            SchemaMigrator.migrate(conn, migrations);
            helper.assertTrue(SchemaMigrator.userVersion(conn) == 2,
                    "应用两个迁移后 user_version 必须是 2, 实为 " + SchemaMigrator.userVersion(conn));
            helper.assertTrue(SchemaMigrator.tableExists(conn, "t_one")
                            && SchemaMigrator.tableExists(conn, "t_two"),
                    "两个迁移建的表都必须存在");
            // ALTER 出来的列必须真的可写, 证明第二个迁移确实作用在第一个迁移建的表上。
            exec(conn, "INSERT INTO t_one (id, name, note) VALUES (1, 'a', 'n')");

            // 重复迁移必须是 no-op: 若误重跑, CREATE TABLE 会因表已存在而抛错。
            SchemaMigrator.migrate(conn, migrations);
            helper.assertTrue(SchemaMigrator.userVersion(conn) == 2, "重复迁移后版本不得变化");
            helper.assertTrue(countRows(conn, "t_one") == 1, "重复迁移不得影响既有数据");
        } finally {
            MiningDb.close(conn);
        }
        helper.succeed();
    }

    /** 迁移中途失败必须整体回滚, 不留半迁移的库。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void failedMigrationRollsBackEntirely(GameTestHelper helper) {
        Connection conn = MiningDb.openInMemory();
        try {
            List<List<String>> broken = List.of(
                    List.of("CREATE TABLE t_ok (id INTEGER PRIMARY KEY)"),
                    List.of("CREATE TABLE t_partial (id INTEGER PRIMARY KEY)",
                            "THIS IS NOT VALID SQL"));

            boolean failed = false;
            try {
                SchemaMigrator.migrate(conn, broken);
            } catch (MiningStoreException expected) {
                failed = true;
            }

            helper.assertTrue(failed, "非法 DDL 必须抛出而不是静默跳过");
            helper.assertTrue(SchemaMigrator.userVersion(conn) == 0,
                    "整体回滚后 user_version 必须回到 0, 实为 " + SchemaMigrator.userVersion(conn));
            helper.assertTrue(!SchemaMigrator.tableExists(conn, "t_ok"),
                    "同一事务内先前迁移建的表也必须一并回滚");
            helper.assertTrue(!SchemaMigrator.tableExists(conn, "t_partial"),
                    "失败迁移中已执行的语句必须回滚");
        } finally {
            MiningDb.close(conn);
        }
        helper.succeed();
    }

    /** 库版本高于代码支持版本时必须拒绝启动, 而不是用旧代码去写新结构。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void newerSchemaIsRejected(GameTestHelper helper) {
        Connection conn = MiningDb.openInMemory();
        try {
            exec(conn, "PRAGMA user_version=9");
            boolean rejected = false;
            try {
                SchemaMigrator.migrate(conn, List.of(List.of("CREATE TABLE t (id INTEGER PRIMARY KEY)")));
            } catch (MiningStoreException expected) {
                rejected = true;
            }
            helper.assertTrue(rejected, "库版本高于代码支持版本时必须拒绝");
            helper.assertTrue(!SchemaMigrator.tableExists(conn, "t"), "被拒绝时不得执行任何 DDL");
        } finally {
            MiningDb.close(conn);
        }
        helper.succeed();
    }

    /** WAL 下已提交的数据必须真的落进文件: 关闭连接重开后仍在。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void committedRowsSurviveConnectionReopen(GameTestHelper helper) {
        Path dir;
        try {
            dir = Files.createTempDirectory("miningdim-store-test");
        } catch (Exception e) {
            throw new MiningStoreException("无法创建临时目录", e);
        }
        Path dbPath = dir.resolve("reopen.db");
        try {
            Connection first = MiningDb.openAt(dbPath);
            try {
                SchemaMigrator.migrate(first, List.of(
                        List.of("CREATE TABLE durable (id INTEGER PRIMARY KEY, payload TEXT NOT NULL)")));
                exec(first, "INSERT INTO durable (id, payload) VALUES (7, 'kept')");
            } finally {
                MiningDb.close(first);
            }

            Connection second = MiningDb.openAt(dbPath);
            try {
                helper.assertTrue(SchemaMigrator.userVersion(second) == 1,
                        "重开后 user_version 必须仍是 1");
                helper.assertTrue(countRows(second, "durable") == 1,
                        "已提交的行必须在重开后仍然存在");
                helper.assertTrue("kept".equals(singleText(second, "SELECT payload FROM durable WHERE id=7")),
                        "重开后列值必须原样保留");
            } finally {
                MiningDb.close(second);
            }
        } finally {
            deleteQuietly(dir);
        }
        helper.succeed();
    }

    private static void exec(Connection conn, String sql) {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new MiningStoreException("测试语句执行失败: " + sql, e);
        }
    }

    private static int countRows(Connection conn, String table) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getInt(1) : -1;
        } catch (SQLException e) {
            throw new MiningStoreException("计数失败: " + table, e);
        }
    }

    private static String singleText(Connection conn, String sql) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new MiningStoreException("查询失败: " + sql, e);
        }
    }

    private static void deleteQuietly(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // 临时目录清理失败不影响断言结果; WAL 附属文件可能仍被占用。
                }
            });
        } catch (Exception ignored) {
            // 同上。
        }
    }
}
