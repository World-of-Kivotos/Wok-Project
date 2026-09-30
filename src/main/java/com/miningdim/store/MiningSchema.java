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

    /**
     * 版本 4: 给 {@code pending_payout} 补 {@code seller_uuid} 索引。
     *
     * 为什么不能改 V1 而必须新开一版: 迁移由 user_version 门控只跑一次, 老存档的 user_version 早已推进过
     * V1, 不会重新执行其中的 CREATE 语句。把索引塞进 V1 只会让"从这版代码起新建的库"与"已经跑过旧版 V1
     * 的老存档"结构分歧、而 user_version 相同, 这类不一致事后无法诊断 —— 与本文件顶部的铁律直接冲突。
     *
     * 为什么需要这个索引: 访问 pending_payout 的三条语句 (MarketDaoSqlite.java 的两处待领款 SELECT 与一处
     * DELETE) 全部按 seller_uuid 过滤, 调用点分别是 MarketEngine.settlePendingOnLogin (卖家每次上线触发)
     * 与 market.pendingPayout (卖家每次打开收件箱面板触发), 两者都跑在服务端主线程上。没有该列的索引时,
     * 这三条语句在 pending_payout 上都是全表扫描, 且扫描频率随在线卖家数与他们的操作频次线性放大。
     *
     * 只加索引、不加清理: 行只在该卖家本人 drain 时被删除, 退坑/长期离线卖家的待领款行会永久驻留、表单调
     * 增长。这本该配一条清理或归档策略, 但保留期是需要主控拍板的业务数值 (例如"离线满多久视为弃置"),
     * 本次迁移刻意不臆造这个数字, 只解决索引缺失导致的扫描代价, 清理逻辑留给后续单独的、附带具体保留期
     * 拍板结果的变更。
     */
    private static final List<String> V4 = List.of(
            "CREATE INDEX idx_pending_payout_seller ON pending_payout(seller_uuid)");

    /**
     * 版本 5: 称号模块 (wok-title) 的持有表与佩戴表, 结构见 docs/Title_System_DesignSpec.md 第四章。
     *
     * title_owned 以 (player_uuid, title_id) 为主键: 发放按主键幂等 (INSERT OR IGNORE), 重复发放既不报错也不
     * 覆盖最早的 source / source_ref / granted_at —— "这个称号最初是怎么来的"是审计事实, 后来的重复发放不该改写它。
     * 所有读取都按 player_uuid 过滤, 复合主键的最左列即覆盖这条访问路径, 因此不另建索引。
     *
     * title_equipped 一人一行 (player_uuid 主键), 不佩戴即无行。刻意不对 title_owned 加外键: 回收称号时由称号
     * 模块在同一事务里一并卸下, 而"定义被数据包删除"只影响显示、不删任何行 (定义恢复后自动复原), 外键表达不了
     * 这层语义, 反而会让将来的批量修复脚本多一道顺序约束。
     *
     * 与 V4 同理只能新开一版而不能并进旧版本: 老存档的 user_version 已越过旧版本, 不会重跑其中的 CREATE 语句。
     */
    private static final List<String> V5 = List.of(
            "CREATE TABLE title_owned ("
                    + "player_uuid TEXT NOT NULL, "
                    + "title_id TEXT NOT NULL, "
                    + "source TEXT NOT NULL, "
                    + "source_ref TEXT, "
                    + "granted_at INTEGER NOT NULL, "
                    + "PRIMARY KEY (player_uuid, title_id))",
            "CREATE TABLE title_equipped ("
                    + "player_uuid TEXT PRIMARY KEY, "
                    + "title_id TEXT NOT NULL)");

    /**
     * 版本 6: 赞助专属称号 (设计文档第十三章) 的资格表与记录表, 结构见 docs/Title_System_DesignSpec.md 13.6,
     * 各一人一行 (player_uuid 主键)。
     *
     * title_sponsor.expires_at 为 NULL 表示永久资格。资格到期或被撤销时 title_custom 的记录保留 (续期后原样恢复),
     * 因此两表之间不加外键。title_custom.updated_at 是最近一次由玩家本人成功修改的时间, 即修改冷却的起点
     * (管理员清除冷却时置 0); locked / locked_by 记录管理员的修改锁定。
     *
     * 为什么另开 V6 而不并进 V5: V5 已随称号模块 P1 的提交落地, 跑过那一版的库停在 user_version=5、只有持有与
     * 佩戴两表。并进 V5 的话这些库永远不会补建这两张表, 称号系统会在每次登录读赞助资格时因缺表失败 —— 与本文件
     * 顶部的铁律同理, 已应用过的迁移只能追加、不能改。
     */
    private static final List<String> V6 = List.of(
            "CREATE TABLE title_sponsor ("
                    + "player_uuid TEXT PRIMARY KEY, "
                    + "granted_by TEXT NOT NULL, "
                    + "granted_at INTEGER NOT NULL, "
                    + "expires_at INTEGER)",
            "CREATE TABLE title_custom ("
                    + "player_uuid TEXT PRIMARY KEY, "
                    + "text TEXT NOT NULL, "
                    + "colors TEXT NOT NULL, "
                    + "bold INTEGER NOT NULL, "
                    + "updated_at INTEGER NOT NULL, "
                    + "locked INTEGER NOT NULL DEFAULT 0, "
                    + "locked_by TEXT)");

    /**
     * 版本 7: 成就模块 (wok-achievement) 的待领取奖励、成就点余额、成就点流水 (结构见
     * docs/Achievement_System_DesignSpec.md 7.2) 与统计项每日上限计数 (6.1)。
     *
     * achievement_reward 以 (player_uuid, advancement_id) 为主键, 这条主键就是防重键: 管理员撤销进度后再次授予,
     * 写入按主键忽略, 不会重复发奖; claimed_at 为 NULL 表示待领取。tier / points / title_id 是获得那一刻的元数据
     * 快照, 之后调整数值不影响已产生的记录。achievement_points 一人一行, lifetime 只增不减。
     * achievement_point_ledger 只追加、不删改 (撤销进度也不收回已领取的奖励, 流水里保留原记录)。
     *
     * achievement_daily_counter 记"某玩家某计数键在某个 UTC 纪元日已经计了几次", 主键 (玩家, 键, 天), 结构参照 V2 的
     * daily_counters, 但把日期放进主键: 每天一行, 判断上限与加一可以在一条 upsert 里完成, 不需要先读后写。
     *
     * 奖励、余额与每日计数的读取全部按 player_uuid 过滤, 主键的最左列即覆盖这条访问路径; 流水当前只写不读 (审计用),
     * 因此都不另建索引。与 V6 同理, 只能在末尾追加。
     */
    private static final List<String> V7 = List.of(
            "CREATE TABLE achievement_reward ("
                    + "player_uuid TEXT NOT NULL, "
                    + "advancement_id TEXT NOT NULL, "
                    + "tier TEXT NOT NULL, "
                    + "points INTEGER NOT NULL, "
                    + "title_id TEXT, "
                    + "earned_at INTEGER NOT NULL, "
                    + "claimed_at INTEGER, "
                    + "PRIMARY KEY (player_uuid, advancement_id))",
            "CREATE TABLE achievement_points ("
                    + "player_uuid TEXT PRIMARY KEY, "
                    + "balance INTEGER NOT NULL, "
                    + "lifetime INTEGER NOT NULL)",
            "CREATE TABLE achievement_point_ledger ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "player_uuid TEXT NOT NULL, "
                    + "delta INTEGER NOT NULL, "
                    + "reason TEXT NOT NULL, "
                    + "ref TEXT, "
                    + "at INTEGER NOT NULL)",
            "CREATE TABLE achievement_daily_counter ("
                    + "player_uuid TEXT NOT NULL, "
                    + "counter_key TEXT NOT NULL, "
                    + "day INTEGER NOT NULL, "
                    + "count INTEGER NOT NULL, "
                    + "PRIMARY KEY (player_uuid, counter_key, day))");

    /**
     * 版本 8: 成就模块市场成就的按买家记账 (docs/Achievement_System_DesignSpec.md 6.6), 每一对 (卖家, 买家) 一行:
     * trades 是合格交易笔数, counted_volume 是这位买家贡献给这位卖家的计数成交额 (受 1,000,000 与买家当时系统收入封顶,
     * 只增不减)。成交时一条 upsert 按主键更新; 卖家侧的汇总按主键最左列 seller_uuid 查, 买家侧的笔数按 buyer_uuid 查,
     * 后者另建索引。不存 IP: 同 IP 判定只在交易瞬间比较当时的连接。与 V7 同理只能在末尾追加。
     */
    private static final List<String> V8 = List.of(
            "CREATE TABLE achievement_market_partner ("
                    + "seller_uuid TEXT NOT NULL, "
                    + "buyer_uuid TEXT NOT NULL, "
                    + "trades INTEGER NOT NULL, "
                    + "counted_volume INTEGER NOT NULL, "
                    + "PRIMARY KEY (seller_uuid, buyer_uuid))",
            "CREATE INDEX idx_achievement_market_partner_buyer ON achievement_market_partner(buyer_uuid)");

    /**
     * 版本 9: 自管区模块 (wok-district) 的十二张表与四个触发器, 结构见 docs/District_Backend_Design.md 第四章与 22.11。
     *
     * <ul>
     *   <li>district_academy: 学院, 主键 academy_id, sort_order 唯一 (自管区列表按它排序)。六校由模块开服时
     *       INSERT OR IGNORE, 不在迁移里写死行: 学院名单是运营数据, 迁移只管结构。</li>
     *   <li>district_member: 学院名单一行 = 住户一名。id 自增即入学顺序; player_uuid 唯一 = 一人只属于一个学院
     *       (含已解绑学院); name_lower 唯一防止大小写不同的同一个名字被加两次 (由 Java 用 Locale.ROOT 算好写入,
     *       不用 SQLite 的 lower(): 后者只处理 ASCII, 两边口径会分叉)。按学院列名单走 (academy_id, id) 索引。</li>
     *   <li>district: 学院与领地的一次绑定, 解绑后行保留作归档。部分唯一索引 ux_district_live_academy 保证每个学院
     *       最多一个未解绑的自管区, 解绑后可以重新绑定。已解绑的自管区没有区务长 (CHECK)。</li>
     *   <li>district_permission / district_plot_permission: 公共区域与地块开关表, 每一格一行、只有开或关
     *       (enabled NOT NULL 且只取 0/1): 写进 Flan 的"不设置"会回退到上一层, 这正是要堵死的漏。地块格随地块级联删除。</li>
     *   <li>district_plot: 现存地块。(district_id, plot_no) 唯一; 部分唯一索引 ux_district_plot_owner 保证一人最多
     *       一块地 (对全部地块生效, 含已解绑自管区的地块); 户主与冻结中的原户主互斥, 名 / UUID / 时间成组出现 (CHECK)。</li>
     *   <li>district_plot_friend: 地块朋友, id 自增即存储顺序, 同一块地里 UUID 与小写名各自唯一, 随地块级联删除。
     *       按玩家查朋友身份走 player_uuid 索引, 首次登录找待生效朋友走 name_lower 的部分索引。</li>
     *   <li>district_plot_tombstone: 已删地块的墓碑; 刻意不对 district 建外键, 与记录表同属审计性质。</li>
     *   <li>district_log / district_plot_log: 只追加的操作记录, 性质同 V7 的流水, 刻意不建外键 —— 地块删除后它的记录
     *       必须留下来挪进墓碑。地块记录用 tenure 区分属于哪一任户主, 收回时只把任期 +1, 不搬行。两表的 action 与
     *       actor_role 只收契约枚举值 (前端的标签表按它们索引, 枚举外的值会显示成空白)。</li>
     *   <li>district_seen_player: 进过服的玩家, "有没有进过服"与最后在线时间的唯一来源。</li>
     *   <li>district_notice: 离线玩家的聊天通知队列 (阶段 3 追加在末尾, 22.11)。kind 不进 CHECK (加一种通知不用开迁移,
     *       投递时不认识的 kind 记 WARN 后删掉); args_json 是格式化好的字符串数组; district_id、plot_id 只为排障, 不建外键
     *       (同两张记录表)。按 (recipient_uuid, id) 取、按插入顺序发; 每人最多 30 条、保留 30 天由模块自己删。
     *       V9 在追加这张表时还没有发布过 (铁律只约束已发布的迁移); 用追加之前的开发构建开过的库停在 9 却没有这张表,
     *       模块开服时查出来降级为只读并记 ERROR (附手工补建的两条语句, 绝不让人删库), 不在迁移之外自动补建。</li>
     * </ul>
     *
     * 四个触发器是服务层之外的最后一道闸: 新地块必须空置、户主必须是该区学院成员、区务长必须是本学院成员、
     * 还是户主或区务长的成员不能被删除。服务层会先按契约报业务码, 触发器只拦代码 bug。
     * 每个 CREATE TRIGGER 是一个字符串: sqlite-jdbc 把整个触发器当一条语句准备, 体内的分号不会把它拆开。
     *
     * 业务上限 (单价 ≤ 1,000,000、边长 ≤ 1024) 刻意不进 CHECK: 数值还没拍板, 写进 CHECK 的话每改一次都得开一版迁移。
     */
    private static final List<String> V9 = List.of(
            "CREATE TABLE district_academy ("
                    + "academy_id TEXT PRIMARY KEY, "
                    + "short_name TEXT NOT NULL, "
                    + "full_name TEXT NOT NULL, "
                    + "sort_order INTEGER NOT NULL UNIQUE, "
                    + "created_at INTEGER NOT NULL)",

            "CREATE TABLE district_member ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "player_uuid TEXT NOT NULL UNIQUE, "
                    + "player_name TEXT NOT NULL, "
                    + "name_lower TEXT NOT NULL UNIQUE, "
                    + "academy_id TEXT NOT NULL REFERENCES district_academy(academy_id), "
                    + "joined_at INTEGER NOT NULL, "
                    + "added_by_uuid TEXT, "
                    + "added_by_name TEXT NOT NULL, "
                    + "sync_status TEXT NOT NULL CHECK (sync_status IN ('synced','pending','failed')), "
                    + "sync_error TEXT)",
            "CREATE INDEX idx_district_member_academy ON district_member(academy_id, id)",

            "CREATE TABLE district ("
                    + "district_id TEXT PRIMARY KEY, "
                    + "academy_id TEXT NOT NULL REFERENCES district_academy(academy_id), "
                    + "display_name TEXT NOT NULL, "
                    + "dimension TEXT NOT NULL, "
                    + "min_x INTEGER NOT NULL, "
                    + "min_z INTEGER NOT NULL, "
                    + "max_x INTEGER NOT NULL, "
                    + "max_z INTEGER NOT NULL, "
                    + "rules_json TEXT NOT NULL DEFAULT '[]', "
                    + "warden_uuid TEXT, "
                    + "warden_name TEXT, "
                    + "unit_price INTEGER NOT NULL, "
                    + "min_side INTEGER NOT NULL, "
                    + "max_side INTEGER NOT NULL, "
                    + "purchase_open INTEGER NOT NULL DEFAULT 0, "
                    + "next_plot_no INTEGER NOT NULL DEFAULT 1, "
                    + "flan_claim_id TEXT, "
                    + "needs_reconcile INTEGER NOT NULL DEFAULT 0, "
                    + "created_at INTEGER NOT NULL, "
                    + "created_by_name TEXT NOT NULL, "
                    + "unbound_at INTEGER, "
                    + "unbound_by_name TEXT, "
                    + "unbound_member_count INTEGER, "
                    + "unbound_plot_count INTEGER, "
                    + "CHECK (min_x <= max_x AND min_z <= max_z), "
                    + "CHECK (unit_price >= 1), "
                    + "CHECK (min_side >= 1 AND min_side <= max_side), "
                    + "CHECK (purchase_open IN (0,1)), "
                    + "CHECK (needs_reconcile IN (0,1)), "
                    + "CHECK (next_plot_no >= 1), "
                    + "CHECK ((warden_uuid IS NULL) = (warden_name IS NULL)), "
                    + "CHECK ((unbound_at IS NULL) = (unbound_by_name IS NULL)), "
                    + "CHECK (unbound_at IS NULL OR warden_uuid IS NULL))",
            "CREATE UNIQUE INDEX ux_district_live_academy ON district(academy_id) WHERE unbound_at IS NULL",

            "CREATE TABLE district_permission ("
                    + "district_id TEXT NOT NULL REFERENCES district(district_id), "
                    + "permission_id TEXT NOT NULL, "
                    + "audience TEXT NOT NULL CHECK (audience IN ('resident','outsider','district')), "
                    + "enabled INTEGER NOT NULL CHECK (enabled IN (0,1)), "
                    + "PRIMARY KEY (district_id, permission_id, audience))",

            "CREATE TABLE district_plot ("
                    + "plot_id TEXT PRIMARY KEY, "
                    + "district_id TEXT NOT NULL REFERENCES district(district_id), "
                    + "plot_no INTEGER NOT NULL, "
                    + "code TEXT NOT NULL, "
                    + "min_x INTEGER NOT NULL, "
                    + "min_z INTEGER NOT NULL, "
                    + "max_x INTEGER NOT NULL, "
                    + "max_z INTEGER NOT NULL, "
                    + "owner_uuid TEXT, "
                    + "owner_name TEXT, "
                    + "frozen_owner_uuid TEXT, "
                    + "frozen_owner_name TEXT, "
                    + "frozen_at INTEGER, "
                    + "tenure INTEGER NOT NULL DEFAULT 1, "
                    + "sync_status TEXT NOT NULL CHECK (sync_status IN ('synced','failed')), "
                    + "sync_error TEXT, "
                    + "flan_claim_id TEXT, "
                    + "created_at INTEGER NOT NULL, "
                    + "UNIQUE (district_id, plot_no), "
                    + "CHECK (plot_no >= 1), "
                    + "CHECK (tenure >= 1), "
                    + "CHECK (min_x <= max_x AND min_z <= max_z), "
                    + "CHECK ((owner_uuid IS NULL) = (owner_name IS NULL)), "
                    + "CHECK ((frozen_owner_uuid IS NULL) = (frozen_owner_name IS NULL)), "
                    + "CHECK ((frozen_owner_uuid IS NULL) = (frozen_at IS NULL)), "
                    + "CHECK (owner_uuid IS NULL OR frozen_owner_uuid IS NULL))",
            "CREATE UNIQUE INDEX ux_district_plot_owner ON district_plot(owner_uuid) WHERE owner_uuid IS NOT NULL",
            "CREATE INDEX idx_district_plot_frozen_owner ON district_plot(frozen_owner_uuid) "
                    + "WHERE frozen_owner_uuid IS NOT NULL",
            "CREATE INDEX idx_district_plot_frozen_at ON district_plot(frozen_at) WHERE frozen_at IS NOT NULL",

            "CREATE TABLE district_plot_permission ("
                    + "plot_id TEXT NOT NULL REFERENCES district_plot(plot_id) ON DELETE CASCADE, "
                    + "permission_id TEXT NOT NULL, "
                    + "audience TEXT NOT NULL CHECK (audience IN ('friend','resident','outsider')), "
                    + "enabled INTEGER NOT NULL CHECK (enabled IN (0,1)), "
                    + "PRIMARY KEY (plot_id, permission_id, audience))",

            "CREATE TABLE district_plot_friend ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "plot_id TEXT NOT NULL REFERENCES district_plot(plot_id) ON DELETE CASCADE, "
                    + "player_uuid TEXT NOT NULL, "
                    + "player_name TEXT NOT NULL, "
                    + "name_lower TEXT NOT NULL, "
                    + "added_at INTEGER NOT NULL, "
                    + "added_by_name TEXT NOT NULL, "
                    + "sync_status TEXT NOT NULL CHECK (sync_status IN ('synced','pending')), "
                    + "suspended_at INTEGER, "
                    + "UNIQUE (plot_id, player_uuid), "
                    + "UNIQUE (plot_id, name_lower))",
            "CREATE INDEX idx_district_plot_friend_player ON district_plot_friend(player_uuid)",
            "CREATE INDEX idx_district_plot_friend_pending ON district_plot_friend(name_lower) "
                    + "WHERE sync_status = 'pending'",

            "CREATE TABLE district_plot_tombstone ("
                    + "plot_id TEXT PRIMARY KEY, "
                    + "district_id TEXT NOT NULL, "
                    + "code TEXT NOT NULL, "
                    + "min_x INTEGER NOT NULL, "
                    + "min_z INTEGER NOT NULL, "
                    + "max_x INTEGER NOT NULL, "
                    + "max_z INTEGER NOT NULL, "
                    + "deleted_at INTEGER NOT NULL, "
                    + "deleted_by_uuid TEXT, "
                    + "deleted_by_name TEXT NOT NULL)",
            "CREATE INDEX idx_district_plot_tombstone_district ON district_plot_tombstone(district_id, deleted_at)",

            "CREATE TABLE district_log ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "district_id TEXT NOT NULL, "
                    + "at INTEGER NOT NULL, "
                    + "actor_uuid TEXT, "
                    + "actor_name TEXT NOT NULL, "
                    + "actor_role TEXT NOT NULL CHECK (actor_role IN ('admin','warden','resident','system')), "
                    + "action TEXT NOT NULL CHECK (action IN ('add','remove','appoint','revoke','resync','permission',"
                    + "'createPlot','resizePlot','deletePlot','buyPlot','freezePlot','unfreezePlot','vacatePlot',"
                    + "'suspendFriends','setPlotPricing','setPurchaseOpen')), "
                    + "target_name TEXT, "
                    + "reason TEXT, "
                    + "perm_id TEXT, "
                    + "perm_label TEXT, "
                    + "perm_audience TEXT CHECK (perm_audience IS NULL OR perm_audience IN "
                    + "('resident','outsider','district')), "
                    + "perm_from INTEGER, "
                    + "perm_to INTEGER, "
                    + "from_min_x INTEGER, from_min_z INTEGER, from_max_x INTEGER, from_max_z INTEGER, "
                    + "to_min_x INTEGER, to_min_z INTEGER, to_max_x INTEGER, to_max_z INTEGER, "
                    + "CHECK ((action = 'permission') = (perm_id IS NOT NULL)))",
            "CREATE INDEX idx_district_log_district ON district_log(district_id, at, id)",

            "CREATE TABLE district_plot_log ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "plot_id TEXT NOT NULL, "
                    + "district_id TEXT NOT NULL, "
                    + "tenure INTEGER NOT NULL, "
                    + "at INTEGER NOT NULL, "
                    + "actor_uuid TEXT, "
                    + "actor_name TEXT NOT NULL, "
                    + "actor_role TEXT NOT NULL CHECK (actor_role IN ('owner','admin','warden','system')), "
                    + "action TEXT NOT NULL CHECK (action IN ('create','resize','purchase','addFriend','removeFriend',"
                    + "'suspendFriend','restoreFriend','permission','freeze','unfreeze','vacate')), "
                    + "target_name TEXT, "
                    + "reason TEXT, "
                    + "perm_id TEXT, "
                    + "perm_label TEXT, "
                    + "perm_audience TEXT CHECK (perm_audience IS NULL OR perm_audience IN "
                    + "('friend','resident','outsider')), "
                    + "perm_from INTEGER, "
                    + "perm_to INTEGER, "
                    + "from_min_x INTEGER, from_min_z INTEGER, from_max_x INTEGER, from_max_z INTEGER, "
                    + "to_min_x INTEGER, to_min_z INTEGER, to_max_x INTEGER, to_max_z INTEGER, "
                    + "on_behalf_of_owner INTEGER NOT NULL DEFAULT 0 CHECK (on_behalf_of_owner IN (0,1)), "
                    + "CHECK ((action = 'permission') = (perm_id IS NOT NULL)))",
            "CREATE INDEX idx_district_plot_log_plot ON district_plot_log(plot_id, tenure, at, id)",

            "CREATE TABLE district_seen_player ("
                    + "player_uuid TEXT PRIMARY KEY, "
                    + "player_name TEXT NOT NULL, "
                    + "name_lower TEXT NOT NULL, "
                    + "first_seen_at INTEGER NOT NULL, "
                    + "last_seen_at INTEGER NOT NULL, "
                    + "source TEXT NOT NULL CHECK (source IN ('login','backfill')))",
            "CREATE INDEX idx_district_seen_player_name ON district_seen_player(name_lower, last_seen_at)",

            "CREATE TRIGGER trg_district_plot_insert_vacant "
                    + "BEFORE INSERT ON district_plot "
                    + "WHEN NEW.owner_uuid IS NOT NULL OR NEW.frozen_owner_uuid IS NOT NULL "
                    + "BEGIN SELECT RAISE(ABORT, 'district_plot: a new plot must be vacant'); END",
            "CREATE TRIGGER trg_district_plot_owner_is_member "
                    + "BEFORE UPDATE OF owner_uuid ON district_plot "
                    + "WHEN NEW.owner_uuid IS NOT NULL AND NOT EXISTS ("
                    + "SELECT 1 FROM district_member m JOIN district d ON d.academy_id = m.academy_id "
                    + "WHERE d.district_id = NEW.district_id AND m.player_uuid = NEW.owner_uuid) "
                    + "BEGIN SELECT RAISE(ABORT, 'district_plot: owner must be a member of the district academy'); END",
            "CREATE TRIGGER trg_district_warden_is_member "
                    + "BEFORE UPDATE OF warden_uuid ON district "
                    + "WHEN NEW.warden_uuid IS NOT NULL AND NOT EXISTS ("
                    + "SELECT 1 FROM district_member m "
                    + "WHERE m.player_uuid = NEW.warden_uuid AND m.academy_id = NEW.academy_id) "
                    + "BEGIN SELECT RAISE(ABORT, 'district: warden must be a member of the academy'); END",
            "CREATE TRIGGER trg_district_member_delete_guard "
                    + "BEFORE DELETE ON district_member "
                    + "WHEN EXISTS (SELECT 1 FROM district_plot p WHERE p.owner_uuid = OLD.player_uuid) "
                    + "OR EXISTS (SELECT 1 FROM district d WHERE d.warden_uuid = OLD.player_uuid) "
                    + "BEGIN SELECT RAISE(ABORT, 'district_member: still owns a plot or is a warden'); END",

            // 阶段 3 追加 (设计文档 22.11, P27): V9 从没发布过, 只在末尾追加, 不改上面任何一条。
            "CREATE TABLE district_notice ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "recipient_uuid TEXT NOT NULL, "
                    + "kind TEXT NOT NULL, "
                    + "args_json TEXT NOT NULL DEFAULT '[]', "
                    + "district_id TEXT, "
                    + "plot_id TEXT, "
                    + "created_at INTEGER NOT NULL)",
            "CREATE INDEX idx_district_notice_recipient ON district_notice(recipient_uuid, id)");

    /** 全部迁移, 下标 + 1 即其版本号。 */
    static final List<List<String>> MIGRATIONS = List.of(V1, V2, V3, V4, V5, V6, V7, V8, V9);

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
