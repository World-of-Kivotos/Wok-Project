package com.miningdim.testutil;

import com.miningdim.store.MiningDb;
import com.miningdim.store.MiningSchema;
import com.miningdim.store.MiningStoreException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Comparator;

/**
 * GameTest 用的临时统一库工具 (范式同 {@link MockGameTestPlayers}: 测试脚手架, 不参与运行期逻辑)。
 *
 * 存在的理由是内存库测不出落盘: {@code jdbc:sqlite::memory:} 的 journal_mode 实际为 memory, WAL 根本没参与。
 * 凡是要验证"提交即落盘、崩溃重开后数据还在"的用例, 必须建在真实文件上并真的关闭再打开连接。
 */
public final class TempStoreDb {

    private TempStoreDb() {
    }

    /** 建一个临时目录; 失败直接抛 (测试环境连临时目录都建不出来, 继续跑没有意义)。 */
    public static Path createTempDir() {
        try {
            return Files.createTempDirectory("miningdim-store-test");
        } catch (Exception e) {
            throw new MiningStoreException("无法创建临时目录", e);
        }
    }

    /** 在指定文件上开统一库连接并推进 schema 到最新版。 */
    public static Connection openUnified(Path dbPath) {
        Connection conn = MiningDb.openAt(dbPath);
        MiningSchema.apply(conn);
        return conn;
    }

    /** 尽力删除临时目录; 删不掉不影响断言结果 (WAL 附属文件可能仍被系统占用)。 */
    public static void deleteQuietly(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // 见方法注释: 临时文件清理失败与被测行为无关。
                }
            });
        } catch (Exception ignored) {
            // 同上。
        }
    }
}
