package com.miningdim.store;

import java.sql.Connection;
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

    /** 全部迁移, 下标 + 1 即其版本号。 */
    static final List<List<String>> MIGRATIONS = List.of(V1);

    /** 把连接上的库推进到本版代码支持的最新结构。 */
    public static void apply(Connection conn) {
        SchemaMigrator.migrate(conn, MIGRATIONS);
    }
}
