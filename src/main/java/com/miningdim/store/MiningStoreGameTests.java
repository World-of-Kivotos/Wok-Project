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
                // 与 MiningStore.open() 的真实顺序对齐: V3 回填在 apply() 那一刻已经跑完, 看不到刚导入的
                // 旧库行, 必须补跑一次才能追平 (F006 复核, entry a)。
                MiningSchema.backfillCaseEconomySettled(conn);

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
                helper.assertTrue(singleLong(conn, "SELECT economy_settled FROM case_openings WHERE opening_id='"
                                + LEGACY_OPENING_ID + "'") == 1L,
                        "旧库导入行的付款证据只存在于旧版 SavedData, bundle_operations 账本里永远查无此笔, "
                                + "结构上等价于早于 30 天保留期的孤儿; 补跑一次回填必须把它追平为已结算, "
                                + "否则会在 30 天后被当成硬崩溃孤儿真实重复扣款 (F006 复核, entry a)");
            } finally {
                MiningDb.close(conn);
            }
        } finally {
            TempStoreDb.deleteQuietly(dir);
        }
        helper.succeed();
    }

    /**
     * V3 迁移回填规则的判定表覆盖: 账本证据 (COMPLETED / CHARGED / REFUNDED / 查无) 与 30 天保留期、
     * case 自身状态 (COMMITTED / RESERVED) 交叉出的六种情形必须逐一落在正确的 economy_settled 值上 ——
     * 漏判一种就会在真服上表现为该赦免的没赦免 (老玩家被反复重复扣费), 或不该赦免的被误赦免 (真崩溃孤儿
     * 永远拿不到应得的皮肤/退款)。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void caseSettlementBackfillGrandfathersOnlyPrunedEvidence(GameTestHelper helper) {
        Connection conn = MiningDb.openInMemory();
        try {
            SchemaMigrator.migrate(conn, MiningSchema.MIGRATIONS.subList(0, 2));
            helper.assertTrue(SchemaMigrator.userVersion(conn) == 2,
                    "推进到 V2 后 user_version 必须是 2, 实为 " + SchemaMigrator.userVersion(conn));

            long now = System.currentTimeMillis();
            long beyondRetention = now - 60L * 24 * 60 * 60 * 1000;

            // 甲: COMMITTED + 账本同笔 COMPLETED -> 无争议已结算, 必须赦免。
            insertCaseOpening(conn, JIA_OPENING_ID, JIA_ASSET_ID, "COMMITTED", now);
            insertBundleOperation(conn, JIA_OPENING_ID, "COMPLETED", now);

            // 乙: COMMITTED + 账本同笔 CHARGED -> 扣款流程未到终态, 交给登录恢复继续推进, 不得赦免。
            insertCaseOpening(conn, YI_OPENING_ID, YI_ASSET_ID, "COMMITTED", now);
            insertBundleOperation(conn, YI_OPENING_ID, "CHARGED", now);

            // 丙: COMMITTED + 账本同笔 REFUNDED -> 货已发但钱已退, 自相矛盾数据不得被这次迁移悄悄抹平。
            insertCaseOpening(conn, BING_OPENING_ID, BING_ASSET_ID, "COMMITTED", now);
            insertBundleOperation(conn, BING_OPENING_ID, "REFUNDED", now);

            // 丁: COMMITTED + 账本查无此笔 + 早于 30 天保留期 -> 证据只可能是被 prune 删的, 必须赦免。
            insertCaseOpening(conn, DING_OPENING_ID, DING_ASSET_ID, "COMMITTED", beyondRetention);

            // 戊: COMMITTED + 账本查无此笔 + 仍在保留期内 -> 真正的硬崩溃孤儿, 必须保留 0 交给补扣款。
            insertCaseOpening(conn, WU_OPENING_ID, WU_ASSET_ID, "COMMITTED", now);

            // 己: RESERVED + 账本查无此笔 + 早于 30 天保留期 -> 未提交订单不进入赦免判定, 必须保持默认 0。
            insertCaseOpening(conn, JI_OPENING_ID, JI_ASSET_ID, "RESERVED", beyondRetention);

            MiningSchema.apply(conn);
            helper.assertTrue(SchemaMigrator.userVersion(conn) == MiningSchema.MIGRATIONS.size(),
                    "推进到最新版后 user_version 必须等于迁移总数 " + MiningSchema.MIGRATIONS.size()
                            + ", 实为 " + SchemaMigrator.userVersion(conn));

            long jia = economySettled(conn, JIA_OPENING_ID);
            helper.assertTrue(jia == 1L,
                    "甲(COMMITTED+账本COMPLETED)必须被赦免, economy_settled 应为 1, 实为 " + jia);

            long yi = economySettled(conn, YI_OPENING_ID);
            helper.assertTrue(yi == 0L,
                    "乙(COMMITTED+账本CHARGED)扣款尚未到终态, economy_settled 应为 0, 实为 " + yi);

            long bing = economySettled(conn, BING_OPENING_ID);
            helper.assertTrue(bing == 0L,
                    "丙(COMMITTED+账本REFUNDED)是自相矛盾数据, economy_settled 应为 0, 实为 " + bing);

            long ding = economySettled(conn, DING_OPENING_ID);
            helper.assertTrue(ding == 1L,
                    "丁(COMMITTED+账本查无+超出30天保留期)必须被赦免, economy_settled 应为 1, 实为 " + ding);

            long wu = economySettled(conn, WU_OPENING_ID);
            helper.assertTrue(wu == 0L,
                    "戊(COMMITTED+账本查无+仍在保留期内)是真孤儿, economy_settled 应为 0, 实为 " + wu);

            long ji = economySettled(conn, JI_OPENING_ID);
            helper.assertTrue(ji == 0L,
                    "己(RESERVED 状态不进入赦免判定), economy_settled 应保持默认 0, 实为 " + ji);
        } finally {
            MiningDb.close(conn);
        }
        helper.succeed();
    }

    /**
     * F006 复核 entry b: 存档若停在 user_version=1 直升 3, V3 回填在同一事务内对着一张空的
     * bundle_operations 判定 —— 此时旧账本 (SavedData) 尚未搬进来, 30 天保留期内的 COMMITTED 行会被
     * 误判成未结算。真正的付款证据要等 {@code EconomyLedgerBootstrap.migrateIfNeeded} 在
     * ServerStartedEvent 才会写进 bundle_operations, 此时必须补跑一次同一套判据把它追平。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void backfillCanBeRerunAfterLedgerEvidenceArrivesLate(GameTestHelper helper) {
        Connection conn = MiningDb.openInMemory();
        try {
            SchemaMigrator.migrate(conn, MiningSchema.MIGRATIONS.subList(0, 2));
            helper.assertTrue(SchemaMigrator.userVersion(conn) == 2,
                    "推进到 V2 后 user_version 必须是 2, 实为 " + SchemaMigrator.userVersion(conn));

            long now = System.currentTimeMillis();
            insertCaseOpening(conn, LATE_EVIDENCE_OPENING_ID, LATE_EVIDENCE_ASSET_ID, "COMMITTED", now);

            // 模拟 user_version 1->3 的直升: 此刻 bundle_operations 是空表 (旧账本尚未搬入), V3 回填只能
            // 看见"账本查无此笔", 而该行仍在 30 天保留期内, 必须保持 0, 不得误赦免。
            MiningSchema.apply(conn);
            helper.assertTrue(SchemaMigrator.userVersion(conn) == MiningSchema.MIGRATIONS.size(),
                    "推进到最新版后 user_version 必须等于迁移总数 " + MiningSchema.MIGRATIONS.size()
                            + ", 实为 " + SchemaMigrator.userVersion(conn));
            long beforeLedgerArrives = economySettled(conn, LATE_EVIDENCE_OPENING_ID);
            helper.assertTrue(beforeLedgerArrives == 0L,
                    "旧账本尚未迁入时, 保留期内的 COMMITTED 行必须保持未结算, 实为 " + beforeLedgerArrives);

            // 模拟 EconomyLedgerBootstrap.migrateIfNeeded 在 ServerStartedEvent 把该笔证据搬进来。
            insertBundleOperation(conn, LATE_EVIDENCE_OPENING_ID, "COMPLETED", now);
            MiningSchema.backfillCaseEconomySettled(conn);
            long afterRerun = economySettled(conn, LATE_EVIDENCE_OPENING_ID);
            helper.assertTrue(afterRerun == 1L,
                    "账本证据到位后补跑回填必须把它追平为已结算, 否则 30 天保留期后会被真实重复扣款, 实为 "
                            + afterRerun);
        } finally {
            MiningDb.close(conn);
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
     * F070: 全新库应用统一 schema 后, {@code pending_payout} 按 {@code seller_uuid} 过滤必须真的走
     * {@link MiningSchema} V4 新建的 idx_pending_payout_seller 索引, 而不仅仅是"索引存在"。
     * 缺该索引时 SQLite 的查询计划会退化成全表 SCAN, 判据必须落在 EXPLAIN QUERY PLAN 的 detail 文本上。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void pendingPayoutSellerIndexIsUsedByTheQueryPlanner(GameTestHelper helper) {
        Connection conn = MiningDb.openInMemory();
        try {
            MiningSchema.apply(conn);
            helper.assertTrue(SchemaMigrator.userVersion(conn) == MiningSchema.MIGRATIONS.size(),
                    "全新库应用统一 schema 后 user_version 必须等于迁移总数 " + MiningSchema.MIGRATIONS.size()
                            + ", 实为 " + SchemaMigrator.userVersion(conn));

            String plan = explainQueryPlanDetail(conn,
                    "SELECT amount FROM pending_payout WHERE seller_uuid = 'x'");
            helper.assertTrue(plan.contains("idx_pending_payout_seller"),
                    "按 seller_uuid 查询 pending_payout 必须走 idx_pending_payout_seller 索引 (缺索引时会是"
                            + " SCAN), 实际查询计划: " + plan);
        } finally {
            MiningDb.close(conn);
        }
        helper.succeed();
    }

    /**
     * F070 核心: 停在 V3 的老库 (idx_pending_payout_seller 尚不存在) 升级到最新版时, {@link SchemaMigrator}
     * 只能补跑 V4, 严禁重跑已经执行过的 V1 (否则 CREATE TABLE 撞已存在的表直接抛错)。升级前后
     * pending_payout 里已有的行必须逐行逐值原样保留, 且升级后同样真走新建的索引。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void legacyDatabaseStoppedAtV3GetsTheIndexOnUpgradeWithoutLosingRows(GameTestHelper helper) {
        Connection conn = MiningDb.openInMemory();
        try {
            SchemaMigrator.migrate(conn, MiningSchema.MIGRATIONS.subList(0, 3));
            helper.assertTrue(SchemaMigrator.userVersion(conn) == 3,
                    "停在 V3 的老库 user_version 必须是 3, 实为 " + SchemaMigrator.userVersion(conn));

            // 老库升级前已有若干条待结款行 (真实存档场景); 记下确切行数与其中两行的金额, 升级后必须一条不少、
            // 一分不差。
            exec(conn, "INSERT INTO pending_payout (seller_uuid, amount, currency, created_at) VALUES "
                    + "('" + LEGACY_PAYOUT_SELLER_ID + "', 1500, 'CREDIT', 9001)");
            exec(conn, "INSERT INTO pending_payout (seller_uuid, amount, currency, created_at) VALUES "
                    + "('" + LEGACY_PAYOUT_SELLER_ID + "', 300, 'CREDIT', 9002)");
            exec(conn, "INSERT INTO pending_payout (seller_uuid, amount, currency, created_at) VALUES "
                    + "('" + LEGACY_PAYOUT_OTHER_SELLER_ID + "', 42, 'CREDIT', 9003)");
            int rowsBefore = countRows(conn, "pending_payout");
            helper.assertTrue(rowsBefore == 3, "升级前必须先落 3 条待结款行, 实为 " + rowsBefore);

            MiningSchema.apply(conn);

            helper.assertTrue(SchemaMigrator.userVersion(conn) == MiningSchema.MIGRATIONS.size(),
                    "老库升级后 user_version 必须推进到迁移总数 " + MiningSchema.MIGRATIONS.size()
                            + ", 实为 " + SchemaMigrator.userVersion(conn));

            String plan = explainQueryPlanDetail(conn,
                    "SELECT amount FROM pending_payout WHERE seller_uuid = 'x'");
            helper.assertTrue(plan.contains("idx_pending_payout_seller"),
                    "老库升级后按 seller_uuid 查询 pending_payout 也必须走新建的索引, 实际查询计划: " + plan);

            int rowsAfter = countRows(conn, "pending_payout");
            helper.assertTrue(rowsAfter == 3,
                    "老库升级 (仅补索引) 不得丢失或增删任何一行待结款, 实为 " + rowsAfter);
            long firstAmount = singleLong(conn,
                    "SELECT amount FROM pending_payout WHERE seller_uuid='" + LEGACY_PAYOUT_SELLER_ID
                            + "' AND created_at=9001");
            helper.assertTrue(firstAmount == 1500L,
                    "老库升级不得改动既有行的列值, created_at=9001 那行 amount 必须仍是 1500, 实为 " + firstAmount);
            long secondAmount = singleLong(conn,
                    "SELECT amount FROM pending_payout WHERE seller_uuid='" + LEGACY_PAYOUT_SELLER_ID
                            + "' AND created_at=9002");
            helper.assertTrue(secondAmount == 300L,
                    "老库升级不得改动既有行的列值, created_at=9002 那行 amount 必须仍是 300, 实为 " + secondAmount);
        } finally {
            MiningDb.close(conn);
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

    private static final String BACKFILL_OWNER_ID = "55555555-5555-4555-8555-555555555555";
    private static final String JIA_OPENING_ID = "a0000000-0000-4000-8000-000000000001";
    private static final String JIA_ASSET_ID = "a0000000-0000-4000-8000-000000000002";
    private static final String YI_OPENING_ID = "a0000000-0000-4000-8000-000000000003";
    private static final String YI_ASSET_ID = "a0000000-0000-4000-8000-000000000004";
    private static final String BING_OPENING_ID = "a0000000-0000-4000-8000-000000000005";
    private static final String BING_ASSET_ID = "a0000000-0000-4000-8000-000000000006";
    private static final String DING_OPENING_ID = "a0000000-0000-4000-8000-000000000007";
    private static final String DING_ASSET_ID = "a0000000-0000-4000-8000-000000000008";
    private static final String WU_OPENING_ID = "a0000000-0000-4000-8000-000000000009";
    private static final String WU_ASSET_ID = "a0000000-0000-4000-8000-00000000000a";
    private static final String JI_OPENING_ID = "a0000000-0000-4000-8000-00000000000b";
    private static final String JI_ASSET_ID = "a0000000-0000-4000-8000-00000000000c";
    private static final String LATE_EVIDENCE_OPENING_ID = "a0000000-0000-4000-8000-00000000000d";
    private static final String LATE_EVIDENCE_ASSET_ID = "a0000000-0000-4000-8000-00000000000e";

    private static final String LEGACY_PAYOUT_SELLER_ID = "b0000000-0000-4000-8000-000000000001";
    private static final String LEGACY_PAYOUT_OTHER_SELLER_ID = "b0000000-0000-4000-8000-000000000002";

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

    /** 插入一条 V1 版 case_openings 行 (V3 的 economy_settled 列此时尚不存在, 取列 DEFAULT 0)。 */
    private static void insertCaseOpening(Connection conn, String openingId, String assetId,
                                           String status, long createdAt) {
        exec(conn, "INSERT INTO case_openings "
                + "(opening_id,owner_uuid,case_id,credit_cost,azure_cost,status,asset_id,skin_id,rarity,"
                + "gun_id,display_id,reel_json,stop_index,created_at,updated_at) VALUES ('"
                + openingId + "','" + BACKFILL_OWNER_ID + "','standard',500,0,'" + status + "','"
                + assetId + "','skin.violet','LEGENDARY','tacz:ak47','violet','[]',3,"
                + createdAt + "," + createdAt + ")");
    }

    /** 插入一条与某开箱同 operation_id 的账本行, 模拟 V3 回填要读取的幂等证据。 */
    private static void insertBundleOperation(Connection conn, String operationId, String status, long createdAt) {
        exec(conn, "INSERT INTO bundle_operations "
                + "(operation_id,domain,player_id,credit_amount,azure_amount,status,created_at) VALUES ('"
                + operationId + "','CASE_OPENING','" + BACKFILL_OWNER_ID + "',500,0,'" + status + "',"
                + createdAt + ")");
    }

    private static long economySettled(Connection conn, String openingId) {
        return singleLong(conn, "SELECT economy_settled FROM case_openings WHERE opening_id='" + openingId + "'");
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

    /**
     * 拼接 {@code EXPLAIN QUERY PLAN} 每一行的 detail 文本 (可能多行, 如涉及连接查询)。
     * 判据必须落在这段文本上而不是"索引对象是否存在于 sqlite_master"——后者测不出查询实际有没有用到索引。
     */
    private static String explainQueryPlanDetail(Connection conn, String sql) {
        StringBuilder detail = new StringBuilder();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("EXPLAIN QUERY PLAN " + sql)) {
            while (rs.next()) {
                detail.append(rs.getString("detail")).append('\n');
            }
        } catch (SQLException e) {
            throw new MiningStoreException("EXPLAIN QUERY PLAN 查询失败: " + sql, e);
        }
        return detail.toString();
    }

}
