package com.miningdim.store;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * 统一库 {@code miningdim.db} 的 schema 定义, 是全部经济事实表结构的唯一真源。
 *
 * 此前跳蚤市场与开箱各自在 DAO 的 {@code initSchema()} 里用 {@code CREATE TABLE IF NOT EXISTS} 建表。
 * 那种写法对【已存在的表】是彻底的 no-op: 给现存表加一列不会发生, 且没有任何地方记录当前结构到了哪一版。
 * 结构定义因此从 DAO 移到这里, 由 {@link SchemaMigrator} 按版本推进 —— DAO 只负责读写行, 不再拥有结构。
 *
 * 铁律: 已发布的迁移严禁修改, 只能在 {@link #MIGRATIONS} 末尾追加。改动既有迁移会让已升级的存档与新存档
 * 结构分歧而 user_version 相同, 这类不一致事后无法诊断。
 */
public final class MiningSchema {

    private MiningSchema() {
    }

    /**
     * 版本 1: 合并原 miningdim_market.db 与 miningdim_cases.db 的全部表, 外加 {@link StoreMeta} 的元数据表。
     *
     * 表结构与两个旧库逐列一致 (含列序), 这是 {@link LegacyStoreImport} 能用 {@code INSERT INTO t SELECT * FROM
     * legacy.t} 整表搬迁的前提; 列序一旦分歧, 导入会立刻抛错而不是静默错位。
     *
     * 不用 IF NOT EXISTS: 迁移由 user_version 门控只跑一次, 表已存在意味着版本记录与实际结构不符, 必须抛。
     */
    private static final List<String> V1 = List.of(
            "CREATE TABLE meta ("
                    + "key TEXT PRIMARY KEY, "
                    + "value TEXT NOT NULL)",

            "CREATE TABLE listings ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "seller_uuid TEXT NOT NULL, "
                    + "seller_name TEXT NOT NULL, "
                    + "item_id TEXT NOT NULL, "
                    + "item_nbt BLOB NOT NULL, "
                    + "count INTEGER NOT NULL, "
                    + "unit_price INTEGER NOT NULL, "
                    + "currency TEXT NOT NULL, "
                    + "created_at INTEGER NOT NULL, "
                    + "status TEXT NOT NULL)",
            "CREATE TABLE transactions ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "listing_id INTEGER NOT NULL, "
                    + "buyer_uuid TEXT NOT NULL, "
                    + "seller_uuid TEXT NOT NULL, "
                    + "item_id TEXT NOT NULL, "
                    + "count INTEGER NOT NULL, "
                    + "unit_price INTEGER NOT NULL, "
                    + "total INTEGER NOT NULL, "
                    + "fee INTEGER NOT NULL, "
                    + "created_at INTEGER NOT NULL)",
            "CREATE TABLE pending_payout ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "seller_uuid TEXT NOT NULL, "
                    + "amount INTEGER NOT NULL, "
                    + "currency TEXT NOT NULL, "
                    + "created_at INTEGER NOT NULL)",
            "CREATE TABLE base_values ("
                    + "item_id TEXT PRIMARY KEY, "
                    + "v0 INTEGER NOT NULL, "
                    + "updated_by TEXT, "
                    + "updated_at INTEGER NOT NULL)",
            "CREATE INDEX idx_listings_status_item ON listings(status, item_id)",
            "CREATE INDEX idx_listings_seller ON listings(seller_uuid)",
            "CREATE INDEX idx_txn_buyer ON transactions(buyer_uuid)",
            "CREATE INDEX idx_txn_seller ON transactions(seller_uuid)",

            "CREATE TABLE case_openings ("
                    + "opening_id TEXT PRIMARY KEY, "
                    + "owner_uuid TEXT NOT NULL, "
                    + "case_id TEXT NOT NULL, "
                    + "credit_cost INTEGER NOT NULL, "
                    + "azure_cost INTEGER NOT NULL, "
                    + "status TEXT NOT NULL, "
                    + "asset_id TEXT NOT NULL UNIQUE, "
                    + "skin_id TEXT NOT NULL, "
                    + "rarity TEXT NOT NULL, "
                    + "gun_id TEXT NOT NULL, "
                    + "display_id TEXT NOT NULL, "
                    + "reel_json TEXT NOT NULL, "
                    + "stop_index INTEGER NOT NULL, "
                    + "created_at INTEGER NOT NULL, "
                    + "updated_at INTEGER NOT NULL)",
            "CREATE TABLE skin_assets ("
                    + "asset_id TEXT PRIMARY KEY, "
                    + "owner_uuid TEXT NOT NULL, "
                    + "skin_id TEXT NOT NULL, "
                    + "rarity TEXT NOT NULL, "
                    + "gun_id TEXT NOT NULL, "
                    + "display_id TEXT NOT NULL, "
                    + "source_opening_id TEXT NOT NULL UNIQUE, "
                    + "acquired_at INTEGER NOT NULL, "
                    + "trade_locked_until INTEGER NOT NULL, "
                    + "FOREIGN KEY(source_opening_id) REFERENCES case_openings(opening_id))",
            "CREATE INDEX idx_case_openings_owner_status ON case_openings(owner_uuid,status)",
            "CREATE INDEX idx_skin_assets_owner ON skin_assets(owner_uuid)",
            "CREATE INDEX idx_skin_assets_owner_skin ON skin_assets(owner_uuid,skin_id)");

    /**
     * 版本 2: 钱包、双币幂等操作账本与每日计数从 Minecraft SavedData 迁入本库。
     *
     * SavedData 最长 5 分钟才落一次盘 (MinecraftServer.tickServer 每 6000 tick 触发 saveEverything), 而 SQLite
     * 提交即落盘。钱留在 SavedData、资产在 SQLite 时, 崩溃后恒定是"资产在、钱回滚了", 且该窗口套在所有经济
     * 写入上。把钱搬进同一个库是让 BEGIN/COMMIT 真正覆盖"扣钱 + 发资产"的前提。
     *
     * bundle_operations 补了原 SavedData 结构没有的 created_at: 原结构无时间戳, 既无法做终态回收也无法审计定位。
     * daily_counters 把原来的 "玩家UUID|计数键" 拼接串拆成两列, 计数种类 (扣费 / faucet) 由 kind 区分 ——
     * 原实现是两张各自独立的 map, 合成一张表后主键 (玩家, 键, 种类) 直接表达了这个三元唯一性。
     */
    private static final List<String> V2 = List.of(
            "CREATE TABLE wallets ("
                    + "player_id TEXT PRIMARY KEY, "
                    + "credit INTEGER NOT NULL DEFAULT 0, "
                    + "azure INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE bundle_operations ("
                    + "operation_id TEXT PRIMARY KEY, "
                    + "domain TEXT NOT NULL, "
                    + "player_id TEXT NOT NULL, "
                    + "credit_amount INTEGER NOT NULL, "
                    + "azure_amount INTEGER NOT NULL, "
                    + "status TEXT NOT NULL, "
                    + "created_at INTEGER NOT NULL)",
            "CREATE INDEX idx_bundle_ops_player ON bundle_operations(player_id, domain)",
            "CREATE TABLE daily_counters ("
                    + "player_id TEXT NOT NULL, "
                    + "counter_key TEXT NOT NULL, "
                    + "kind TEXT NOT NULL, "
                    + "amount INTEGER NOT NULL, "
                    + "day_stamp INTEGER NOT NULL, "
                    + "credit_carry REAL NOT NULL DEFAULT 0, "
                    + "PRIMARY KEY (player_id, counter_key, kind))");

    /**
     * 版本 3: 给 {@code case_openings} 加 {@code economy_settled} 列, 把开箱结算的幂等锚从可回收的
     * {@code bundle_operations} 账本行搬到开箱库自己的表上。
     *
     * 此前 {@code CaseOpeningService.isEconomySettled} 靠查 {@code bundle_operations} 里对应 operation_id
     * 是否存在 CHARGED/COMPLETED 行来判定一笔开箱是否已扣过款; 而 {@code EconomySystem} 每次启动都会 prune
     * 掉 30 天前的 COMPLETED/REFUNDED 行。凭据被删后, 登录恢复会把这类无证据的 COMMITTED 行当成"扣款中途
     * 崩溃的孤儿", 对玩家真扣一次 CREDIT/AZURE —— 皮肤明明已经发到手, 却在 30 天窗口后被反复重复扣费。
     * 把结算状态落到 case_openings 自己身上, 使其不再随账本保留期漂移。
     *
     * 回填 (第二条语句) 的四种情形与取值依据:
     * <ul>
     *   <li>账本里查到该笔的 COMPLETED 证据 -> 置 1。这是无争议的已结算, 有终态凭证。</li>
     *   <li>账本里查到该笔的 CHARGED 或 REFUNDED 证据 -> 保持 0。CHARGED 说明扣款流程尚未走到终态, 交给既有
     *       的登录恢复逻辑继续推进; REFUNDED 与 case_openings.status='COMMITTED' 本身自相矛盾 (一边说货已发,
     *       一边说钱已退), 这类冲突数据不该被这次迁移悄悄抹平, 必须继续走既有的隔离/人工路径。</li>
     *   <li>账本里查无此笔、且该行创建时间早于 30 天保留期 -> 置 1。这类行的证据只可能是被本仓自己的
     *       prune 删掉的: 在修复前, 登录恢复对任何无证据的 COMMITTED 行都会补扣款并写回一条新的账本证据,
     *       所以一条创建时间已经超出保留期、却仍然查无证据的行, 只有"证据存在过但被 prune 删了"这一种解释,
     *       不可能是从未结算过。这里的 2592000000 (30 天毫秒数) 与 {@code EconomySystem} 里的保留期常量
     *       同源, 但在此处刻死为字面量是刻意的: 这是一条一次性的历史数据迁移, 描述的是"升级那一刻" 30 天
     *       保留期造成的既成事实, 不应该跟随日后配置调整而改变对历史行的判断。
     *       代价: 极少数保留期外的"真崩溃孤儿" (资产从未真正发出、账本也丢了) 会被这条规则一并赦免、不再
     *       补扣款。权衡过的替代方案是让每个老玩家在升级后的第一次登录都按其历史开箱笔数逐笔重新扣款 ——
     *       那正是这次要修的 Critical 本身, 不可接受, 因此选择赦免这极少数真孤儿。</li>
     *   <li>账本里查无此笔、且该行仍在 30 天保留期之内 -> 保持 0。这才是真正的硬崩溃孤儿 (资产已发、账本
     *       却从未写入或已被显式作废), 保留 0 让登录恢复照常补扣款, 是正确处置, 不应被赦免。</li>
     * </ul>
     *
     * 这条 UPDATE 只在 user_version 推进到 3 的那一刻跑一次 (由 {@link SchemaMigrator} 门控)。但两类数据
     * 恰好都发生在这一刻【之后】才落进库里, 回填因此够不着它们:
     * <ul>
     *   <li>{@link LegacyStoreImport} 从 miningdim_cases.db 搬入的 COMMITTED 开箱行 —— 它们的付款证据只
     *       存在于早已删除的旧版 SavedData, bundle_operations 里永远查不到, 结构上等价于"账本查无此笔",
     *       会一直卡在 economy_settled=0 直到被当作硬崩溃孤儿重新扣款。</li>
     *   <li>存档若停在 user_version=1 (case_openings 已在统一库、钱包仍在 SavedData), 本次 apply 会在同一
     *       个事务里先跑 V2 建出空的 bundle_operations、再跑 V3 对着这张空表判定; 真正的付款证据要等
     *       {@code EconomyLedgerBootstrap.migrateIfNeeded} 在 ServerStartedEvent (晚于本类所在的
     *       ServerAboutToStartEvent) 才会被搬进来, 同样早已错过这次判定。</li>
     * </ul>
     * {@link #backfillCaseEconomySettled} 把这条 UPDATE 抽成可重复调用的独立入口, 供 {@link MiningStore}
     * 在 {@link LegacyStoreImport} 之后、{@code EconomySystem} 在旧账本迁移之后各自补跑一次, 让这两类
     * 迟到的数据也能被同一套判据追平。
     */
    private static final String BACKFILL_ECONOMY_SETTLED_SQL =
            "UPDATE case_openings SET economy_settled=1 WHERE status='COMMITTED' AND NOT EXISTS "
                    + "(SELECT 1 FROM bundle_operations b WHERE b.operation_id=case_openings.opening_id "
                    + "AND b.status IN ('CHARGED','REFUNDED')) AND "
                    + "(EXISTS (SELECT 1 FROM bundle_operations b2 WHERE b2.operation_id=case_openings.opening_id "
                    + "AND b2.status='COMPLETED') OR created_at < (strftime('%s','now')*1000 - 2592000000))";

    private static final List<String> V3 = List.of(
            "ALTER TABLE case_openings ADD COLUMN economy_settled INTEGER NOT NULL DEFAULT 0",
            BACKFILL_ECONOMY_SETTLED_SQL);

    /** 全部迁移, 下标 + 1 即其版本号。 */
    static final List<List<String>> MIGRATIONS = List.of(V1, V2, V3);

    /** 把连接上的库推进到本版代码支持的最新结构。 */
    public static void apply(Connection conn) {
        SchemaMigrator.migrate(conn, MIGRATIONS);
    }

    /**
     * 重新执行 V3 的结算回填判据 (见上方 javadoc)。该 UPDATE 只依赖 case_openings 与 bundle_operations
     * 的当前内容, 对已经是目标值的行重复执行无副作用, 可以在旧库导入、旧账本迁移等"迟到数据到位"的
     * 时间点安全地重复调用。
     */
    public static void backfillCaseEconomySettled(Connection conn) {
        try (Statement statement = conn.createStatement()) {
            statement.execute(BACKFILL_ECONOMY_SETTLED_SQL);
        } catch (SQLException e) {
            throw new MiningStoreException("重跑开箱结算回填失败", e);
        }
    }
}
