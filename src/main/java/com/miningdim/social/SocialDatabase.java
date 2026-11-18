package com.miningdim.social;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** 单写者增量存储；运行期写入和过期清理只由后台线程执行。 */
final class SocialDatabase implements AutoCloseable {
    static final long RETENTION_MILLIS = Duration.ofDays(7).toMillis();
    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/social");
    private static final int WRITE_BATCH_SIZE = 128;
    private final Connection writer;
    private final Connection reader;
    private final ArrayBlockingQueue<List<Row>> pending = new ArrayBlockingQueue<>(8192);
    private final Map<String, Row> pendingRows = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor executor;
    private final AtomicLong writeOrder = new AtomicLong();
    private volatile RuntimeException failure;
    private long nextCleanup;
    private boolean started;

    record Row(String kind, String id, String thread, long createdAt, String json, long order) {
        Row(String kind, String id, String thread, long createdAt, String json) {
            this(kind, id, thread, createdAt, json, 0L);
        }
    }

    SocialDatabase(Path file) {
        Connection openedWriter = null;
        Connection openedReader = null;
        try {
            Class.forName("org.sqlite.JDBC");
            String url = "jdbc:sqlite:" + file.toAbsolutePath().toString().replace('\\', '/');
            openedWriter = DriverManager.getConnection(url);
            try (Statement statement = openedWriter.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=FULL");
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("CREATE TABLE IF NOT EXISTS social_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
                statement.execute("CREATE TABLE IF NOT EXISTS social_records (kind TEXT NOT NULL, id TEXT NOT NULL, thread TEXT NOT NULL, created_at INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(kind,id))");
                statement.execute("CREATE INDEX IF NOT EXISTS social_history ON social_records(kind,thread,created_at DESC,id DESC)");
                statement.execute("CREATE INDEX IF NOT EXISTS social_expiry ON social_records(kind,created_at)");
            }
            openedReader = DriverManager.getConnection(url);
            try (Statement statement = openedReader.createStatement()) {
                statement.execute("PRAGMA query_only=ON");
                try (ResultSet result = statement.executeQuery("SELECT COALESCE(MAX(rowid),0) FROM social_records")) {
                    result.next();
                    writeOrder.set(result.getLong(1));
                }
            }
        } catch (ClassNotFoundException | SQLException error) {
            closeAfterFailure(openedReader, error);
            closeAfterFailure(openedWriter, error);
            throw new IllegalStateException("无法打开社交数据库: " + file, error);
        }
        writer = openedWriter;
        reader = openedReader;
        executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "wok-social-writer");
            thread.setDaemon(false);
            return thread;
        });
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setRemoveOnCancelPolicy(true);
    }

    boolean initialized() {
        try (Statement statement = reader.createStatement();
             ResultSet result = statement.executeQuery("SELECT value FROM social_meta WHERE key='schema'")) {
            if (!result.next()) return false;
            if (!"1".equals(result.getString(1))) {
                throw new IllegalStateException("不支持的社交数据库版本: " + result.getString(1));
            }
            return true;
        } catch (SQLException error) {
            throw new IllegalStateException("无法读取社交数据库版本", error);
        }
    }

    /** 仅开服迁移调用，所有旧记录和迁移标记在同一事务提交。 */
    void initialize(List<Row> rows) {
        try {
            writer.setAutoCommit(false);
            upsert(ordered(rows));
            try (Statement statement = writer.createStatement()) {
                statement.executeUpdate("INSERT INTO social_meta(key,value) VALUES('schema','1')");
            }
            writer.commit();
        } catch (SQLException | RuntimeException error) {
            rollback(error);
            throw new IllegalStateException("社交存档迁移失败，原 JSON 保持不变", error);
        } finally {
            autoCommit();
        }
    }

    void start() {
        started = true;
        executor.scheduleWithFixedDelay(this::writeNextBatch, 100L, 100L, TimeUnit.MILLISECONDS);
    }

    void checkHealthy() {
        RuntimeException error = failure;
        if (error != null) throw error;
    }

    /** 在修改内存之前检查；SocialStore 的对象锁保证只有一个提交方。 */
    void checkWritable() {
        checkHealthy();
        if (pending.remainingCapacity() == 0) {
            throw new SocialException("SOCIAL_STORAGE_BUSY", "社交数据保存繁忙，请稍后再试");
        }
    }

    void enqueue(List<Row> rows) {
        checkHealthy();
        List<Row> orderedRows = ordered(rows);
        for (Row row : orderedRows) pendingRows.put(row.kind() + ":" + row.id(), row);
        if (!pending.offer(orderedRows)) {
            for (Row row : orderedRows) pendingRows.remove(row.kind() + ":" + row.id(), row);
            throw new IllegalStateException("社交写入队列容量契约被破坏");
        }
    }

    List<Row> permanent(String kind) {
        return select("SELECT kind,id,thread,created_at,payload,rowid FROM social_records WHERE kind=?", kind);
    }

    List<Row> history(String kind, String thread, long before, int limit, long now) {
        return history(kind, thread, before, "", limit, now);
    }

    List<Row> history(String kind, String thread, long before, String beforeId, int limit, long now) {
        String cursor = before <= 0L ? "" : beforeId.isEmpty() ? " AND created_at<?"
                : " AND (created_at<? OR (created_at=? AND id<?))";
        String tieBreaker = "moment".equals(kind) ? "id" : "rowid";
        String sql = "SELECT kind,id,thread,created_at,payload,rowid FROM social_records WHERE kind=? AND thread=? AND created_at>?"
                + cursor + " ORDER BY created_at DESC," + tieBreaker + " DESC LIMIT ?";
        List<Object> arguments = new ArrayList<>(List.of(kind, thread, now - RETENTION_MILLIS));
        if (before > 0L) {
            arguments.add(before);
            if (!beforeId.isEmpty()) {
                arguments.add(before);
                arguments.add(beforeId);
            }
        }
        arguments.add(limit);
        List<Row> queuedRows = new ArrayList<>(pendingRows.values());
        List<Row> saved = select(sql, arguments.toArray());
        Map<String, Row> visible = new LinkedHashMap<>();
        for (Row row : saved) visible.put(row.id(), row);
        // 刚应答的操作在后台提交前也必须可读，且较新的待写版本覆盖数据库中的旧版本。
        for (Row row : queuedRows) {
            if (row.kind().equals(kind) && row.thread().equals(thread)
                    && row.createdAt() > now - RETENTION_MILLIS && (before <= 0L || row.createdAt() < before
                    || (row.createdAt() == before && !beforeId.isEmpty() && row.id().compareTo(beforeId) < 0))) {
                visible.put(row.id(), row);
            }
        }
        Comparator<Row> order = Comparator.comparingLong(Row::createdAt);
        order = "moment".equals(kind) ? order.thenComparing(Row::id) : order.thenComparingLong(Row::order);
        return visible.values().stream().sorted(order.reversed()).limit(limit).toList();
    }

    Row moment(String id, long now) {
        Row queued = pendingRows.get("moment:" + id);
        if (queued != null) return queued.createdAt() > now - RETENTION_MILLIS ? queued : null;
        List<Row> rows = select("SELECT kind,id,thread,created_at,payload,rowid FROM social_records WHERE kind='moment' AND id=? AND created_at>?",
                id, now - RETENTION_MILLIS);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<Row> select(String sql, Object... arguments) {
        checkHealthy();
        try (PreparedStatement statement = reader.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) statement.setObject(index + 1, arguments[index]);
            try (ResultSet result = statement.executeQuery()) {
                List<Row> rows = new ArrayList<>();
                while (result.next()) {
                    rows.add(new Row(result.getString(1), result.getString(2), result.getString(3),
                            result.getLong(4), result.getString(5), result.getLong(6)));
                }
                return rows;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("无法读取社交记录", error);
        }
    }

    void flush() {
        checkHealthy();
        if (!started) return;
        try {
            executor.submit(() -> {
                while (!pending.isEmpty() && failure == null) writeNextBatch();
                checkHealthy();
            }).get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待社交数据保存时被中断", error);
        } catch (ExecutionException error) {
            throw new IllegalStateException("社交数据保存失败", error.getCause());
        }
    }

    void writeNextBatch() {
        if (failure != null) return;
        List<List<Row>> batch = new ArrayList<>();
        for (List<Row> rows : pending) {
            batch.add(rows);
            if (batch.size() == WRITE_BATCH_SIZE) break;
        }
        try {
            if (!batch.isEmpty()) {
                writer.setAutoCommit(false);
                for (List<Row> rows : batch) upsert(rows);
                writer.commit();
                writer.setAutoCommit(true);
                for (List<Row> rows : batch) {
                    if (pending.poll() != rows) throw new IllegalStateException("社交写入队列顺序被破坏");
                    for (Row row : rows) pendingRows.remove(row.kind() + ":" + row.id(), row);
                }
            }
            long now = System.currentTimeMillis();
            if (now >= nextCleanup) {
                int deleted;
                try (PreparedStatement statement = writer.prepareStatement(
                        "DELETE FROM social_records WHERE rowid IN (SELECT rowid FROM social_records WHERE kind IN ('moment','comment','world','direct') AND created_at<=? LIMIT 128)")) {
                    statement.setLong(1, now - RETENTION_MILLIS);
                    deleted = statement.executeUpdate();
                }
                nextCleanup = deleted == 128 ? now : now + 60_000L;
            }
        } catch (SQLException | RuntimeException error) {
            rollback(error);
            failure = new IllegalStateException("社交存储写入失败，请联系管理员", error);
            LOGGER.error("社交后台保存失败，本批 {} 个操作未确认；停止接受后续操作", batch.size(), error);
        }
    }

    private void upsert(List<Row> rows) throws SQLException {
        try (PreparedStatement statement = writer.prepareStatement(
                "INSERT INTO social_records(rowid,kind,id,thread,created_at,payload) VALUES(?,?,?,?,?,?) ON CONFLICT(kind,id) DO UPDATE SET thread=excluded.thread,created_at=excluded.created_at,payload=excluded.payload")) {
            for (Row row : rows) {
                statement.setLong(1, row.order());
                statement.setString(2, row.kind());
                statement.setString(3, row.id());
                statement.setString(4, row.thread());
                statement.setLong(5, row.createdAt());
                statement.setString(6, row.json());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private List<Row> ordered(List<Row> rows) {
        return rows.stream().map(row -> new Row(row.kind(), row.id(), row.thread(), row.createdAt(),
                row.json(), writeOrder.incrementAndGet())).toList();
    }

    private void rollback(Throwable error) {
        try {
            writer.rollback();
        } catch (SQLException rollbackError) {
            error.addSuppressed(rollbackError);
        }
    }

    private void autoCommit() {
        try {
            writer.setAutoCommit(true);
        } catch (SQLException error) {
            throw new IllegalStateException("社交数据库无法恢复事务状态", error);
        }
    }

    private static void closeAfterFailure(Connection connection, Throwable error) {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException closeError) {
            error.addSuppressed(closeError);
        }
    }

    @Override
    public void close() {
        try {
            flush();
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(30L, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("社交后台写入线程未停止");
                }
                reader.close();
                writer.close();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待社交后台线程关闭时被中断", error);
            } catch (SQLException error) {
                throw new IllegalStateException("无法关闭社交数据库", error);
            }
        }
    }
}
