package com.miningdim.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 把合库之前遗留的独立库文件导入统一库 {@code miningdim.db}。
 *
 * 只有跳蚤市场真的有存量风险: {@code miningdim_market.db} 已随 main 上线, 真服存档里可能已有挂单、流水与
 * 待结款。开箱库 {@code miningdim_cases.db} 从未上线, 正常情况下不存在, 但同样按流程处理 —— 少写一条分支的
 * 代价是某天真的存在时静默丢数据。
 *
 * 导入语义 (三个条件同时满足才搬):
 * <ol>
 *   <li>meta 表里没有该旧库的导入标记;</li>
 *   <li>旧库文件存在;</li>
 *   <li>统一库中该组表全为空。</li>
 * </ol>
 * 第三条是防重复导入的实质防线。若旧库文件存在、无标记、而统一库已有业务行, 说明有人把旧库文件塞回了一个
 * 已经在跑的世界 —— 此时继续导入会凭空多出一批重复挂单, 因此直接抛异常拒绝启动, 交人处理。
 *
 * 旧库文件导入后【不删除】, 保留作回滚保险。删除的收益只有省几 KB, 代价是出问题时无从回退。
 */
public final class LegacyStoreImport {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/store");

    /** 合库前跳蚤市场的独立库文件名。 */
    public static final String LEGACY_MARKET_DB_FILE = "miningdim_market.db";
    /** 合库前开箱账本的独立库文件名。 */
    public static final String LEGACY_CASE_DB_FILE = "miningdim_cases.db";

    static final String META_MARKET_IMPORTED = "imported_market_at";
    static final String META_CASE_IMPORTED = "imported_cases_at";

    /** 附加旧库时用的 schema 名; 与 main/temp 不冲突即可。 */
    private static final String LEGACY_SCHEMA = "legacy";

    /** 市场四张表。导入顺序即此顺序。 */
    private static final List<String> MARKET_TABLES =
            List.of("listings", "transactions", "pending_payout", "base_values");
    /** 开箱两张表。skin_assets 有指向 case_openings 的外键, 必须后插。 */
    private static final List<String> CASE_TABLES = List.of("case_openings", "skin_assets");

    private LegacyStoreImport() {
    }

    /**
     * 检测并导入世界存档目录下的两个旧库。
     *
     * @param conn      已完成 schema 迁移的统一库连接
     * @param worldRoot 世界存档根目录 (旧库与新库同目录)
     * @param nowMillis 导入时刻, 写进导入标记供事后追溯
     */
    public static void importLegacyDatabases(Connection conn, Path worldRoot, long nowMillis) {
        importLegacy(conn, worldRoot.resolve(LEGACY_MARKET_DB_FILE),
                META_MARKET_IMPORTED, MARKET_TABLES, nowMillis);
        importLegacy(conn, worldRoot.resolve(LEGACY_CASE_DB_FILE),
                META_CASE_IMPORTED, CASE_TABLES, nowMillis);
    }

    private static void importLegacy(Connection conn, Path legacyPath, String markerKey,
                                     List<String> tables, long nowMillis) {
        if (StoreMeta.get(conn, markerKey) != null) {
            return;
        }
        if (!Files.isRegularFile(legacyPath)) {
            return;
        }
        assertTargetEmpty(conn, legacyPath, tables);

        attach(conn, legacyPath);
        try {
            copyInTransaction(conn, legacyPath, markerKey, tables, nowMillis);
        } finally {
            detach(conn);
        }
    }

    /**
     * 目标表必须全空。非空说明统一库已在使用, 而旧库文件却还在且未打标记 —— 无法判断哪些行是旧库的、
     * 哪些是新库自己产生的, 任何自动处置都可能造成重复计入玩家资产, 只能拒绝启动。
     */
    private static void assertTargetEmpty(Connection conn, Path legacyPath, List<String> tables) {
        for (String table : tables) {
            long rows = countRows(conn, "main." + table);
            if (rows != 0) {
                throw new MiningStoreException("检测到旧库 " + legacyPath.getFileName()
                        + " 但统一库的 " + table + " 已有 " + rows + " 行且无导入标记; "
                        + "无法判定是否会重复导入, 拒绝启动。请人工核对后移走旧库文件或清理统一库");
            }
        }
    }

    private static void copyInTransaction(Connection conn, Path legacyPath, String markerKey,
                                          List<String> tables, long nowMillis) {
        boolean autoCommit = setAutoCommit(conn, false);
        try {
            long total = 0;
            for (String table : tables) {
                if (!legacyTableExists(conn, table)) {
                    // 旧库来自更早的版本, 少几张表是可能的; 缺表等价于零行, 不是错误。
                    continue;
                }
                // 按【旧库】的列名清单搬迁, 而不再是 SELECT * 依赖新旧两侧列数与列序完全一致: 统一库的表结构
                // 会随迁移演进 (例如追加新列), 旧库不会跟着变。由旧库列集驱动意味着统一库日后新增的列在导入
                // 后取的是各自 DDL 里的默认值; 反过来, 旧库有而统一库没有的列, INSERT 仍会因目标列不存在而
                // 直接抛错、不会静默错位 —— 原先靠"列序一致"守住的那条底线, 现在靠"旧库列名必须都在目标表
                // 里存在"继续守住。
                String columns = String.join(",", legacyColumns(conn, legacyPath, table));
                exec(conn, "INSERT INTO main." + table + " (" + columns + ")"
                        + " SELECT " + columns + " FROM " + LEGACY_SCHEMA + "." + table);
            }
            for (String table : tables) {
                long source = legacyTableExists(conn, table)
                        ? countRows(conn, LEGACY_SCHEMA + "." + table)
                        : 0;
                long target = countRows(conn, "main." + table);
                if (source != target) {
                    throw new MiningStoreException("导入 " + legacyPath.getFileName() + " 的 " + table
                            + " 行数不一致: 旧库 " + source + " 行, 新库 " + target + " 行");
                }
                total += target;
            }
            StoreMeta.put(conn, markerKey, Long.toString(nowMillis));
            conn.commit();
            LOGGER.info("[miningdim] 已导入旧库 {} 共 {} 行, 旧文件保留作回滚保险",
                    legacyPath.getFileName(), total);
        } catch (RuntimeException | SQLException failure) {
            rollback(conn, failure);
            throw failure instanceof RuntimeException runtime
                    ? runtime
                    : new MiningStoreException("导入旧库失败: " + legacyPath, failure);
        } finally {
            setAutoCommit(conn, autoCommit);
        }
    }

    /** ATTACH 不能在事务内执行, 故必须早于 BEGIN。文件路径走参数绑定, 避免路径里的引号被当作 SQL。 */
    private static void attach(Connection conn, Path legacyPath) {
        try (PreparedStatement ps = conn.prepareStatement(
                "ATTACH DATABASE ? AS " + LEGACY_SCHEMA)) {
            ps.setString(1, legacyPath.toString().replace('\\', '/'));
            ps.execute();
        } catch (SQLException e) {
            throw new MiningStoreException("附加旧库失败: " + legacyPath, e);
        }
    }

    private static void detach(Connection conn) {
        exec(conn, "DETACH DATABASE " + LEGACY_SCHEMA);
    }

    private static boolean legacyTableExists(Connection conn, String tableName) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM " + LEGACY_SCHEMA + ".sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new MiningStoreException("探测旧库表失败: " + tableName, e);
        }
    }

    /**
     * 读出旧库该表的列名清单, 按 cid 顺序 (即建表时的列序)。表名只来自本类内的 MARKET_TABLES/CASE_TABLES
     * 常量, 不是外部输入, 拼进 PRAGMA 语句是安全的 —— PRAGMA 不支持参数绑定, 只能拼接。
     */
    private static List<String> legacyColumns(Connection conn, Path legacyPath, String table) {
        List<String> columns = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "PRAGMA " + LEGACY_SCHEMA + ".table_info(" + table + ")")) {
            while (rs.next()) {
                columns.add(rs.getString("name"));
            }
        } catch (SQLException e) {
            throw new MiningStoreException("读取旧库 " + legacyPath.getFileName()
                    + " 的 " + table + " 表结构失败", e);
        }
        if (columns.isEmpty()) {
            throw new MiningStoreException("旧库 " + legacyPath.getFileName() + " 的表 " + table
                    + " 存在却读不出任何列, 旧库文件可能已损坏, 拒绝导入");
        }
        return columns;
    }

    private static long countRows(Connection conn, String qualifiedTable) {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + qualifiedTable)) {
            if (!rs.next()) {
                throw new MiningStoreException("COUNT 查询无结果: " + qualifiedTable);
            }
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new MiningStoreException("统计行数失败: " + qualifiedTable, e);
        }
    }

    private static void exec(Connection conn, String sql) {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new MiningStoreException("执行语句失败: " + sql, e);
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
            primary.addSuppressed(rollbackFailure);
        }
    }
}
