package com.example.flinkcdcsync.sink;

import com.example.flinkcdcsync.bean.DatabaseConfig;
import com.example.flinkcdcsync.bean.RowChange;
import com.example.flinkcdcsync.bean.TableInfo;
import com.example.flinkcdcsync.manager.DynamicShardedConnectionManager;
import com.example.flinkcdcsync.manager.GroupedExecutorManager;
import com.example.flinkcdcsync.manager.TableLevelQueueManager;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 表级 Sink：负责单个表的具体数据批量写入与分区管理。
 * <p>
 * 本地可运行版本中，它等价于 Flink 的 RichSinkFunction：从队列中取批次，
 * 经过 WAL 重试后写入目标库；多次失败的事件进入死信队列由定时器重试。
 * </p>
 *
 * @author 50707
 */
@Slf4j
public class TableSinkFunction {

    private final DatabaseConfig targetDB;
    private final TableInfo tableInfo;
    private final DynamicShardedConnectionManager connMgr;
    private final GroupedExecutorManager executorManager;
    private final TableLevelQueueManager.TableQueue queue;
    private final int batchSize;
    private final int maxWalRetries;

    private volatile boolean running = true;
    private volatile boolean hasActiveProcessor = false;
    private volatile long lastProcessTime = System.currentTimeMillis();

    /** 死信队列（单条重试）。设容量上限：目标端长时间不可用时，超出部分丢弃，
     *  依赖下一轮全量重扫补偿（幂等 upsert），保证最终一致且不撑爆 JVM 堆。 */
    private static final int MAX_DEAD_LETTER = 100_000;
    private final ConcurrentLinkedQueue<RowChange> pendingFailedEvents = new ConcurrentLinkedQueue<>();

    private Thread processorThread;
    private ScheduledExecutorService failedEventRetryScheduler;

    public TableSinkFunction(DatabaseConfig targetDB, TableInfo tableInfo,
                             DynamicShardedConnectionManager connMgr,
                             GroupedExecutorManager executorManager,
                             TableLevelQueueManager.TableQueue queue,
                             int batchSize, int maxWalRetries) {
        this.targetDB = targetDB;
        this.tableInfo = tableInfo;
        this.connMgr = connMgr;
        this.executorManager = executorManager;
        this.queue = queue;
        this.batchSize = Math.max(100, batchSize);
        this.maxWalRetries = Math.max(1, maxWalRetries);
    }

    public void start() {
        startFailedEventRetry();
        processorThread = new Thread(this::processorLoop, "TableSink-" + tableInfo.getTableName());
        processorThread.setDaemon(true);
        processorThread.start();
    }

    public void enqueue(RowChange change) {
        // 反压：队列满则自旋等待（由上层控制整体速率）
        // 设 60 秒超时上限：防止目标端长时间不可用时阻塞调度线程，
        // 超时后丢弃事件（下一轮全量重扫会通过幂等 upsert 补偿）。
        long deadline = System.currentTimeMillis() + 60_000;
        while (running && !queue.offer(change)) {
            if (System.currentTimeMillis() > deadline) {
                log.warn("enqueue timeout (60s) for table {}, queue full, dropping event (pk={}), "
                        + "will be re-synced by next full scan", tableInfo.getTableName(), change.getPrimaryKeyValue());
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void processorLoop() {
        while (running) {
            try {
                List<Object> batch = queue.drain(batchSize);
                if (batch.isEmpty()) {
                    Thread.sleep(200);
                    continue;
                }
                hasActiveProcessor = true;
                @SuppressWarnings("unchecked")
                List<RowChange> changes = (List<RowChange>) (List<?>) batch;
                processBatchWithWal(changes);
                lastProcessTime = System.currentTimeMillis();
                hasActiveProcessor = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Processor loop error for table {}", tableInfo.getTableName(), e);
                hasActiveProcessor = false;
            }
        }
    }

    /**
     * 带 WAL 重试的批次处理：失败重试 maxWalRetries 次，仍失败则降级为单条处理，
     * 单条再失败则进入死信队列。
     */
    private void processBatchWithWal(List<RowChange> changes) {
        int attempt = 0;
        boolean ok = false;
        while (attempt < maxWalRetries && !ok) {
            try {
                processBatchInternal(changes);
                ok = true;
            } catch (SQLException e) {
                attempt++;
                if (attempt >= maxWalRetries) {
                    log.error("Batch failed after {} attempts, degrading to single-row processing, table={}, size={}",
                            maxWalRetries, tableInfo.getTableName(), changes.size(), e);
                    degradeToSingle(changes);
                } else {
                    log.warn("Batch attempt {} failed, will retry, table={}", attempt, tableInfo.getTableName());
                    try {
                        Thread.sleep(500L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    private void degradeToSingle(List<RowChange> changes) {
        for (RowChange c : changes) {
            try {
                processSingleEvent(c);
            } catch (SQLException e) {
                if (pendingFailedEvents.size() >= MAX_DEAD_LETTER) {
                    log.error("Dead-letter exceeded cap ({}), dropping event (re-synced by next full scan), table={}, pk={}",
                            MAX_DEAD_LETTER, tableInfo.getTableName(), c.getPrimaryKeyValue(), e);
                    continue;
                }
                log.error("Single-row processing failed, moved to dead-letter, table={}, pk={}",
                        tableInfo.getTableName(), c.getPrimaryKeyValue(), e);
                pendingFailedEvents.offer(c);
            }
        }
    }

    /** 批次写入：同一连接内事务提交，保证批次原子性 */
    public void processBatchInternal(List<RowChange> changes) throws SQLException {
        if (changes.isEmpty()) {
            return;
        }
        Connection conn = null;
        try {
            conn = connMgr.getConnection(targetDB);
            conn.setAutoCommit(false);
            for (RowChange change : changes) {
                executeOne(conn, change);
            }
            conn.commit();
        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ignore) {
                }
            }
            throw e;
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException ignore) {
                }
                connMgr.releaseConnection();
            }
        }
    }

    /** 单条写入（死信队列重试用） */
    public void processSingleEvent(RowChange change) throws SQLException {
        Connection conn = null;
        try {
            conn = connMgr.getConnection(targetDB);
            conn.setAutoCommit(true);
            executeOne(conn, change);
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException ignore) {
                }
                connMgr.releaseConnection();
            }
        }
    }

    private void executeOne(Connection conn, RowChange change) throws SQLException {
        if (change.getOpType() == RowChange.OpType.DELETE) {
            executeDelete(conn, change);
        } else {
            executeUpsert(conn, change);
        }
    }

    private void executeUpsert(Connection conn, RowChange change) throws SQLException {
        Set<String> validCols = tableInfo.getColumns().stream().collect(Collectors.toSet());
        List<String> cols = new ArrayList<>();
        List<Object> vals = new ArrayList<>();
        for (Map.Entry<String, Object> e : change.getAfter().entrySet()) {
            if (validCols.contains(e.getKey()) && e.getValue() != null) {
                cols.add(e.getKey());
                vals.add(e.getValue());
            }
        }
        if (cols.isEmpty()) {
            return;
        }
        String colList = String.join(",", cols);
        String placeholders = cols.stream().map(c -> "?").collect(Collectors.joining(","));
        String updateList = cols.stream().map(c -> c + "=VALUES(" + c + ")").collect(Collectors.joining(","));
        String sql = "INSERT INTO " + quote(tableInfo.getTableName()) + " (" + colList + ") VALUES (" + placeholders + ") "
                + "ON DUPLICATE KEY UPDATE " + updateList;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < vals.size(); i++) {
                setParam(ps, i + 1, vals.get(i));
            }
            ps.executeUpdate();
        }
    }

    private void executeDelete(Connection conn, RowChange change) throws SQLException {
        List<String> pkCols = tableInfo.getPrimaryKeyColumns();
        if (pkCols == null || pkCols.isEmpty()) {
            log.warn("No primary key for delete on table {}, skip", tableInfo.getTableName());
            return;
        }
        String where = pkCols.stream().map(c -> c + "=?").collect(Collectors.joining(" AND "));
        String sql = "DELETE FROM " + quote(tableInfo.getTableName()) + " WHERE " + where;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            for (String pk : pkCols) {
                setParam(ps, idx++, change.getAfter().get(pk));
            }
            ps.executeUpdate();
        }
    }

    private void setParam(PreparedStatement ps, int index, Object value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.NULL);
            return;
        }
        if (value instanceof java.time.LocalDateTime) {
            // 以字面量方式绑定 DATETIME，避免 LocalDateTime -> Timestamp 经 JVM 默认时区转换，
            // 再经连接时区写出，导致跨时区搬运时间被偏移（如本机 PDT 与库 Asia/Shanghai 差 15h）。
            ps.setObject(index, value);
        } else if (value instanceof java.time.LocalDate) {
            ps.setDate(index, java.sql.Date.valueOf((java.time.LocalDate) value));
        } else if (value instanceof java.sql.Timestamp) {
            ps.setTimestamp(index, (java.sql.Timestamp) value);
        } else if (value instanceof java.sql.Date) {
            ps.setDate(index, (java.sql.Date) value);
        } else if (value instanceof Number) {
            ps.setObject(index, value);
        } else {
            ps.setObject(index, value);
        }
    }

    private String quote(String ident) {
        return "`" + ident.replace("`", "") + "`";
    }

    private void startFailedEventRetry() {
        failedEventRetryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "FailedEventRetry-" + tableInfo.getTableName());
            t.setDaemon(true);
            return t;
        });
        failedEventRetryScheduler.scheduleAtFixedRate(() -> {
            if (pendingFailedEvents.isEmpty()) {
                return;
            }
            List<RowChange> retry = new ArrayList<>();
            RowChange c;
            while ((c = pendingFailedEvents.poll()) != null) {
                retry.add(c);
            }
            for (RowChange change : retry) {
                try {
                    processSingleEvent(change);
                } catch (SQLException e) {
                    log.error("Dead-letter retry failed again, table={}, pk={}", tableInfo.getTableName(), change.getPrimaryKeyValue(), e);
                    pendingFailedEvents.offer(change);
                }
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    /** 健康检查：处理器假死则重置标志 */
    public boolean isProcessorHealthy(long timeoutMs) {
        if (!queue.isEmpty() && hasActiveProcessor && (System.currentTimeMillis() - lastProcessTime) > timeoutMs) {
            hasActiveProcessor = false;
            return false;
        }
        return true;
    }

    public int pendingFailedSize() {
        return pendingFailedEvents.size();
    }

    /** 队列为空且当前无活跃处理器，表示本表事件已处理完 */
    public boolean isIdle() {
        return queue.isEmpty() && !hasActiveProcessor;
    }

    /** 阻塞等待本表队列处理完成（用于保证一致性后再统计/校验） */
    public void waitIdle(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (isIdle()) {
                return;
            }
            Thread.sleep(50);
        }
    }

    public void close() {
        running = false;
        if (failedEventRetryScheduler != null) {
            failedEventRetryScheduler.shutdownNow();
        }
        if (processorThread != null) {
            processorThread.interrupt();
        }
        // 尝试 flush 剩余队列
        try {
            List<Object> remaining = queue.drain(batchSize * 4);
            if (!remaining.isEmpty()) {
                @SuppressWarnings("unchecked")
                List<RowChange> changes = (List<RowChange>) (List<?>) remaining;
                try {
                    processBatchInternal(changes);
                } catch (SQLException e) {
                    log.warn("Flush on close failed for table {}", tableInfo.getTableName(), e);
                }
            }
        } catch (Exception e) {
            log.warn("Error flushing on close", e);
        }
    }
}
