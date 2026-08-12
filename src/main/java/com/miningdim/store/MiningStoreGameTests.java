package com.miningdim.store;

import com.miningdim.core.MiningConstants;
import com.miningdim.testutil.TempStoreDb;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

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
        Path dir = TempStoreDb.createTempDir();
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
            TempStoreDb.deleteQuietly(dir);
        }
        helper.succeed();
    }

    /** 统一库必须一次性建齐两个旧库的全部表, 少一张就意味着对应业务在真服上会直接崩。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void unifiedSchemaCreatesEveryTable(GameTestHelper helper) {
        Connection conn = MiningDb.openInMemory();
        try {
            MiningSchema.apply(conn);
            helper.assertTrue(SchemaMigrator.userVersion(conn) == MiningSchema.MIGRATIONS.size(),
                    "统一 schema 应用后 user_version 必须等于迁移数量 " + MiningSchema.MIGRATIONS.size()
                            + ", 实为 " + SchemaMigrator.userVersion(conn));
            for (String table : List.of("meta", "listings", "transactions", "pending_payout",
                    "base_values", "case_openings", "skin_assets",
                    "wallets", "bundle_operations", "daily_counters")) {
                helper.assertTrue(SchemaMigrator.tableExists(conn, table), "统一库缺表: " + table);
            }
        } finally {
            MiningDb.close(conn);
        }
        helper.succeed();
    }

    /**
     * 旧库导入必须逐行搬全, 且主键原样保留 —— 挂单 id 会出现在玩家已打开的界面与流水引用里, 换 id 等于换了一张单子。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void legacyDatabasesAreImportedRowForRow(GameTestHelper helper) {
        Path dir = TempStoreDb.createTempDir();
        try {
            writeLegacyMarketDb(dir.resolve(LegacyStoreImport.LEGACY_MARKET_DB_FILE));
            writeLegacyCaseDb(dir.resolve(LegacyStoreImport.LEGACY_CASE_DB_FILE));

            Connection conn = MiningDb.openInMemory();
            try {
                MiningSchema.apply(conn);
                LegacyStoreImport.importLegacyDatabases(conn, dir, 1723000000000L);

                helper.assertTrue(countRows(conn, "listings") == 2,
                        "两条旧挂单必须全部导入, 实为 " + countRows(conn, "listings") + " 条");
                helper.assertTrue("卖家乙".equals(
                                singleText(conn, "SELECT seller_name FROM listings WHERE id=12")),
                        "挂单主键必须原样保留, id=12 的卖家名不符");
                helper.assertTrue(countRows(conn, "transactions") == 1, "流水必须导入");
                helper.assertTrue(singleLong(conn, "SELECT amount FROM pending_payout WHERE id=3") == 777L,
                        "待结款金额必须逐分不差");
                helper.assertTrue(singleLong(conn, "SELECT v0 FROM base_values WHERE item_id='minecraft:diamond'") == 640L,
                        "基准价必须导入");
                helper.assertTrue(countRows(conn, "case_openings") == 1, "开箱记录必须导入");
                helper.assertTrue("skin.violet".equals(
                                singleText(conn, "SELECT skin_id FROM skin_assets WHERE asset_id='"
                                        + LEGACY_ASSET_ID + "'")),
                        "皮肤归属必须导入且外键指向的开箱记录同时在场");

                helper.assertTrue("1723000000000".equals(
                                StoreMeta.get(conn, LegacyStoreImport.META_MARKET_IMPORTED)),
                        "市场导入标记必须写入且带时间戳");
                helper.assertTrue(StoreMeta.get(conn, LegacyStoreImport.META_CASE_IMPORTED) != null,
                        "开箱导入标记必须写入");
            } finally {
                MiningDb.close(conn);
            }
        } finally {
            TempStoreDb.deleteQuietly(dir);
        }
        helper.succeed();
    }

    /** 第二次启动必须靠标记跳过, 否则每次开服都把旧库再灌一遍, 挂单成倍增长。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void secondImportIsSkippedByMarker(GameTestHelper helper) {
        Path dir = TempStoreDb.createTempDir();
        try {
            writeLegacyMarketDb(dir.resolve(LegacyStoreImport.LEGACY_MARKET_DB_FILE));

            Connection conn = MiningDb.openInMemory();
            try {
                MiningSchema.apply(conn);
                LegacyStoreImport.importLegacyDatabases(conn, dir, 1723000000000L);
                LegacyStoreImport.importLegacyDatabases(conn, dir, 1723000099999L);

                helper.assertTrue(countRows(conn, "listings") == 2,
                        "重复导入不得产生重复行, 实为 " + countRows(conn, "listings") + " 条");
                helper.assertTrue("1723000000000".equals(
                                StoreMeta.get(conn, LegacyStoreImport.META_MARKET_IMPORTED)),
                        "标记时间戳必须停在首次导入, 不得被第二次覆盖");
            } finally {
                MiningDb.close(conn);
            }
        } finally {
            TempStoreDb.deleteQuietly(dir);
        }
        helper.succeed();
    }

    /** 统一库已有业务行却又冒出个无标记的旧库文件: 无法判定是否重复, 必须拒绝启动而不是猜。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void importRefusesWhenTargetAlreadyHasRows(GameTestHelper helper) {
        Path dir = TempStoreDb.createTempDir();
        try {
            writeLegacyMarketDb(dir.resolve(LegacyStoreImport.LEGACY_MARKET_DB_FILE));

            Connection conn = MiningDb.openInMemory();
            try {
                MiningSchema.apply(conn);
                exec(conn, "INSERT INTO listings "
                        + "(seller_uuid,seller_name,item_id,item_nbt,count,unit_price,currency,created_at,status) "
                        + "VALUES ('" + LEGACY_SELLER_ID + "','在跑的卖家','minecraft:stone',x'00',1,5,'CREDIT',1,'ACTIVE')");

                boolean refused = false;
                try {
                    LegacyStoreImport.importLegacyDatabases(conn, dir, 1723000000000L);
                } catch (MiningStoreException expected) {
                    refused = true;
                }
                helper.assertTrue(refused, "目标表非空且无导入标记时必须拒绝启动");
                helper.assertTrue(countRows(conn, "listings") == 1,
                        "被拒绝时不得写入任何旧库行, 实为 " + countRows(conn, "listings") + " 条");
                helper.assertTrue(StoreMeta.get(conn, LegacyStoreImport.META_MARKET_IMPORTED) == null,
                        "被拒绝时不得留下导入标记");
            } finally {
                MiningDb.close(conn);
            }
        } finally {
            TempStoreDb.deleteQuietly(dir);
        }
        helper.succeed();
    }

    /**
     * 导入带显式主键的行之后, 新挂单的自增 id 必须越过已导入的最大值。
     * 若自增序列没跟上, 第一笔新挂单就会撞上导入行的主键, 表现为开服后无人能挂单。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void importedIdsDoNotCollideWithNewInserts(GameTestHelper helper) {
        Path dir = TempStoreDb.createTempDir();
        try {
            writeLegacyMarketDb(dir.resolve(LegacyStoreImport.LEGACY_MARKET_DB_FILE));

            Connection conn = MiningDb.openInMemory();
            try {
                MiningSchema.apply(conn);
                LegacyStoreImport.importLegacyDatabases(conn, dir, 1723000000000L);
                exec(conn, "INSERT INTO listings "
                        + "(seller_uuid,seller_name,item_id,item_nbt,count,unit_price,currency,created_at,status) "
                        + "VALUES ('" + LEGACY_SELLER_ID + "','新卖家','minecraft:stone',x'00',1,5,'CREDIT',9,'ACTIVE')");

                long newId = singleLong(conn, "SELECT id FROM listings WHERE seller_name='新卖家'");
                helper.assertTrue(newId > 12L,
                        "新挂单 id 必须大于已导入的最大 id 12, 实为 " + newId);
                helper.assertTrue(countRows(conn, "listings") == 3, "新挂单必须真的落表");
            } finally {
                MiningDb.close(conn);
            }
        } finally {
            TempStoreDb.deleteQuietly(dir);
        }
        helper.succeed();
    }

    private static final String LEGACY_SELLER_ID = "11111111-1111-4111-8111-111111111111";
    private static final String LEGACY_BUYER_ID = "22222222-2222-4222-8222-222222222222";
    private static final String LEGACY_OPENING_ID = "33333333-3333-4333-8333-333333333333";
    private static final String LEGACY_ASSET_ID = "44444444-4444-4444-8444-444444444444";

    /**
     * 造一个合库之前格式的市场库文件。
     * DDL 在此处独立重写而不复用 {@link MiningSchema}: 旧库格式是已经发生的历史, 必须被测试原样钉住 ——
     * 若将来 MiningSchema 改了列而导入路径没跟着改, 这里才会失败, 复用就测不出来了。
     */
    private static void writeLegacyMarketDb(Path file) {
        Connection legacy = MiningDb.openAt(file);
        try {
            exec(legacy, "CREATE TABLE listings ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, seller_uuid TEXT NOT NULL, seller_name TEXT NOT NULL, "
                    + "item_id TEXT NOT NULL, item_nbt BLOB NOT NULL, count INTEGER NOT NULL, "
                    + "unit_price INTEGER NOT NULL, currency TEXT NOT NULL, created_at INTEGER NOT NULL, "
                    + "status TEXT NOT NULL)");
            exec(legacy, "CREATE TABLE transactions ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, listing_id INTEGER NOT NULL, buyer_uuid TEXT NOT NULL, "
                    + "seller_uuid TEXT NOT NULL, item_id TEXT NOT NULL, count INTEGER NOT NULL, "
                    + "unit_price INTEGER NOT NULL, total INTEGER NOT NULL, fee INTEGER NOT NULL, "
                    + "created_at INTEGER NOT NULL)");
            exec(legacy, "CREATE TABLE pending_payout ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, seller_uuid TEXT NOT NULL, amount INTEGER NOT NULL, "
                    + "currency TEXT NOT NULL, created_at INTEGER NOT NULL)");
            exec(legacy, "CREATE TABLE base_values ("
                    + "item_id TEXT PRIMARY KEY, v0 INTEGER NOT NULL, updated_by TEXT, updated_at INTEGER NOT NULL)");

            exec(legacy, "INSERT INTO listings VALUES "
                    + "(11,'" + LEGACY_SELLER_ID + "','卖家甲','minecraft:diamond',x'0a00',3,100,'CREDIT',1000,'ACTIVE')");
            exec(legacy, "INSERT INTO listings VALUES "
                    + "(12,'" + LEGACY_SELLER_ID + "','卖家乙','minecraft:iron_ingot',x'0a01',9,7,'CREDIT',1001,'SOLD')");
            exec(legacy, "INSERT INTO transactions VALUES "
                    + "(5,12,'" + LEGACY_BUYER_ID + "','" + LEGACY_SELLER_ID + "','minecraft:iron_ingot',9,7,63,12,1002)");
            exec(legacy, "INSERT INTO pending_payout VALUES (3,'" + LEGACY_SELLER_ID + "',777,'CREDIT',1003)");
            exec(legacy, "INSERT INTO base_values VALUES ('minecraft:diamond',640,'op',1004)");
        } finally {
            MiningDb.close(legacy);
        }
    }

    /** 造一个合库之前格式的开箱库文件 (外键关系一并造全, 验证导入顺序不会踩外键)。 */
    private static void writeLegacyCaseDb(Path file) {
        Connection legacy = MiningDb.openAt(file);
        try {
            exec(legacy, "CREATE TABLE case_openings ("
                    + "opening_id TEXT PRIMARY KEY, owner_uuid TEXT NOT NULL, case_id TEXT NOT NULL, "
                    + "credit_cost INTEGER NOT NULL, azure_cost INTEGER NOT NULL, status TEXT NOT NULL, "
                    + "asset_id TEXT NOT NULL UNIQUE, skin_id TEXT NOT NULL, rarity TEXT NOT NULL, "
                    + "gun_id TEXT NOT NULL, display_id TEXT NOT NULL, reel_json TEXT NOT NULL, "
                    + "stop_index INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
            exec(legacy, "CREATE TABLE skin_assets ("
                    + "asset_id TEXT PRIMARY KEY, owner_uuid TEXT NOT NULL, skin_id TEXT NOT NULL, "
                    + "rarity TEXT NOT NULL, gun_id TEXT NOT NULL, display_id TEXT NOT NULL, "
                    + "source_opening_id TEXT NOT NULL UNIQUE, acquired_at INTEGER NOT NULL, "
                    + "trade_locked_until INTEGER NOT NULL, "
                    + "FOREIGN KEY(source_opening_id) REFERENCES case_openings(opening_id))");

            exec(legacy, "INSERT INTO case_openings VALUES ('" + LEGACY_OPENING_ID + "','"
                    + LEGACY_BUYER_ID + "','standard',500,0,'COMMITTED','" + LEGACY_ASSET_ID
                    + "','skin.violet','LEGENDARY','tacz:ak47','violet','[]',3,2000,2001)");
            exec(legacy, "INSERT INTO skin_assets VALUES ('" + LEGACY_ASSET_ID + "','"
                    + LEGACY_BUYER_ID + "','skin.violet','LEGENDARY','tacz:ak47','violet','"
                    + LEGACY_OPENING_ID + "',2001,0)");
        } finally {
            MiningDb.close(legacy);
        }
    }

    private static void exec(Connection conn, String sql) {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new MiningStoreException("测试语句执行失败: " + sql, e);
        }
    }

    private static long singleLong(Connection conn, String sql) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) {
                throw new MiningStoreException("查询无结果: " + sql);
            }
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new MiningStoreException("查询失败: " + sql, e);
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

}
