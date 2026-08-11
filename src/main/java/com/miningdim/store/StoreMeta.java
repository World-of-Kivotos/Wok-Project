package com.miningdim.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 统一库的键值元数据表读写。
 *
 * 记录的是"这件一次性的事做过没有"这类事实 (例如旧库是否已导入), 与业务行分开存放, 且与业务写入同事务提交 ——
 * 标记与数据必须同生共死, 否则会出现"数据导了标记没写"(下次重复导入) 或"标记写了数据没导"(数据永久丢失)。
 */
public final class StoreMeta {

    private StoreMeta() {
    }

    /** 读取键值; 不存在返 null (调用方据此判定"从未发生过", 这是 meta 表的正常语义, 不是缺陷)。 */
    public static String get(Connection conn, String key) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM meta WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new MiningStoreException("读取 meta 失败: " + key, e);
        }
    }

    /** 写入或覆盖键值; 由调用方的事务决定何时提交。 */
    public static void put(Connection conn, String key, String value) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO meta (key, value) VALUES (?, ?) "
                        + "ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new MiningStoreException("写入 meta 失败: " + key, e);
        }
    }
}
