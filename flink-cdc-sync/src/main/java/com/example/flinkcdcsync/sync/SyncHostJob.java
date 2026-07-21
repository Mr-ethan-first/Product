package com.example.flinkcdcsync.sync;

import com.example.flinkcdcsync.bean.DatabaseConfig;
import com.example.flinkcdcsync.bean.DatabaseMapping;
import com.example.flinkcdcsync.bean.DbTableIgnore;
import com.example.flinkcdcsync.bean.RowChange;
import com.example.flinkcdcsync.bean.TableInfo;
import com.example.flinkcdcsync.config.GeoDRSyncProperties;
import com.example.flinkcdcsync.enums.DelivationStatusEnum;
import com.example.flinkcdcsync.enums.SyncStateEnum;
import com.example.flinkcdcsync.manager.DynamicShardedConnectionManager;
import com.example.flinkcdcsync.manager.GroupedExecutorManager;
import com.example.flinkcdcsync.manager.TableLevelQueueManager;
import com.example.flinkcdcsync.po.SyncProgress;
import com.example.flinkcdcsync.service.DatabaseMetadataService;
import com.example.flinkcdcsync.sink.DataBaseSinkFunction;
import com.example.flinkcdcsync.util.MatchPattern;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 主机级同步作业（生产中心主机 -> 灾备中心主机，1:1）。
 * <p>
 * 每一轮扫描：发现源主机下所有用户库（排除系统库与忽略库）与所有表（排除忽略表），
 * 自动同步；新增的库 / 表在下一轮被实时纳入。DDL（新增表 / 删除表 / 表结构变更）亦自动同步，
 * 受 ignoreDdlTables（仅忽略 DDL）与 ignoreTables（DML+DDL 全忽略）控制。
 * </p>
 *
 * @author 50707
 */
@Slf4j
public class SyncHostJob {

    private final DatabaseMapping mapping;
    private final GeoDRSyncProperties props;
    private final DynamicShardedConnectionManager connMgr;
    private final GroupedExecutorManager executorManager;
    private final TableLevelQueueManager queueManager;
    private final DatabaseMetadataService metadataService;
    private final Map<String, SyncProgress> progressMap;

    private final String instanceKey;
    private final List<String> ignoreDatabases;
    private final List<String> ignoreTables;
    private final List<String> ignoreDdlTables;
    private final List<DbTableIgnore> ignoreTablesByDb;
    private final List<DbTableIgnore> ignoreDdlTablesByDb;
    private final List<String> commonIgnoreTables;
    private final List<String> commonDdlIgnoreTables;
    private final List<com.example.flinkcdcsync.bean.TransformRule> transformRules;

    /** 每个库一个独立的库级 Sink（db -> sink），保证按真实库名解析表结构与写入 */
    private final Map<String, DataBaseSinkFunction> sinksByDb = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    /** 表级 update_time 水位（db.table -> 水位），用于增量同步 */
    private final Map<String, LocalDateTime> watermarks = new ConcurrentHashMap<>();
    /** 表结构指纹（db.table -> 列签名），用于 ALTER 检测 */
    private final Map<String, String> knownSignatures = new ConcurrentHashMap<>();
    /** 每个库当前已发现的表集合，用于 DROP 检测 */
    private final Map<String, Set<String>> knownTablesByDb = new ConcurrentHashMap<>();

    public SyncHostJob(DatabaseMapping mapping, GeoDRSyncProperties props,
                       DynamicShardedConnectionManager connMgr, GroupedExecutorManager executorManager,
                       TableLevelQueueManager queueManager, DatabaseMetadataService metadataService,
                       Map<String, SyncProgress> progressMap) {
        this.mapping = mapping;
        this.props = props;
        this.connMgr = connMgr;
        this.executorManager = executorManager;
        this.queueManager = queueManager;
        this.metadataService = metadataService;
        this.progressMap = progressMap;
        this.instanceKey = mapping.getInstanceKey();
        this.ignoreDatabases = mapping.getIgnoreDatabases() == null ? new ArrayList<>() : mapping.getIgnoreDatabases();
        this.ignoreTables = mapping.getIgnoreTables() == null ? new ArrayList<>() : mapping.getIgnoreTables();
        this.ignoreDdlTables = mapping.getIgnoreDdlTables() == null ? new ArrayList<>() : mapping.getIgnoreDdlTables();
        this.ignoreTablesByDb = mapping.getIgnoreTablesByDb() == null ? new ArrayList<>() : mapping.getIgnoreTablesByDb();
        this.ignoreDdlTablesByDb = mapping.getIgnoreDdlTablesByDb() == null ? new ArrayList<>() : mapping.getIgnoreDdlTablesByDb();
        this.commonIgnoreTables = mapping.getCommonIgnoreTables() == null ? new ArrayList<>() : mapping.getCommonIgnoreTables();
        this.commonDdlIgnoreTables = mapping.getCommonDdlIgnoreTables() == null ? new ArrayList<>() : mapping.getCommonDdlIgnoreTables();
        this.transformRules = mapping.getTransformRules() == null ? new ArrayList<>() : mapping.getTransformRules();
    }

    public void start() {
        if (running) {
            return;
        }
        // 库级 Sink 采用惰性创建：每发现一个用户库，就为其创建一个绑定真实库名的 DataBaseSinkFunction
        this.running = true;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SyncHostJob-" + instanceKey);
            t.setDaemon(true);
            return t;
        });
        long interval = Math.max(500, props.getEngine().getPollIntervalMs());
        scheduler.scheduleAtFixedRate(this::safeSync, 0, interval, TimeUnit.MILLISECONDS);
        log.info("SyncHostJob started for {}", instanceKey);
    }

    public void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        for (DataBaseSinkFunction s : sinksByDb.values()) {
            try {
                s.close();
            } catch (Exception e) {
                log.warn("close sink failed", e);
            }
        }
        sinksByDb.clear();
        log.info("SyncHostJob stopped for {}", instanceKey);
    }

    /** 按库惰性获取 / 创建库级 Sink（绑定真实库名，避免 db=null 无法解析表结构） */
    private DataBaseSinkFunction getSink(String db) {
        return sinksByDb.computeIfAbsent(db, k -> new DataBaseSinkFunction(
                mapping.toSourceDB(k), mapping.toTargetDB(k),
                connMgr, executorManager, queueManager, metadataService,
                props.getEngine().getBatchSize(), 5, instanceKey + "." + k,
                java.util.Collections.emptySet()));
    }

    /**
     * 判断某库某表是否应「DML + DDL 全忽略」。命中以下任一即忽略：
     * <ol>
     *   <li>旧式扁平 {@code ignoreTables}（支持「库.表」限定）；</li>
     *   <li>通用忽略 {@code commonIgnoreTables}（对所有库生效，按表名精确 / glob / re: 正则）；</li>
     *   <li>层级忽略 {@code ignoreTablesByDb}：库名命中且表名命中该库下的表规则。</li>
     * </ol>
     */
    private boolean isDmlIgnored(String db, String table) {
        if (MatchPattern.matchesTable(ignoreTables, db, table)) {
            return true;
        }
        if (commonIgnoreTables != null && MatchPattern.matchesAny(commonIgnoreTables, table)) {
            return true;
        }
        if (ignoreTablesByDb != null) {
            for (DbTableIgnore e : ignoreTablesByDb) {
                if (e == null || e.getDatabase() == null) {
                    continue;
                }
                if (MatchPattern.matchesAny(List.of(e.getDatabase()), db)
                        && e.getTables() != null && MatchPattern.matchesTable(e.getTables(), db, table)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 判断某库某表是否应「仅忽略 DDL（结构变更），但 DML 数据仍同步」。命中以下任一即忽略 DDL：
     * <ol>
     *   <li>旧式扁平 {@code ignoreDdlTables}（支持「库.表」限定）；</li>
     *   <li>通用 DDL 忽略 {@code commonDdlIgnoreTables}（对所有库生效）；</li>
     *   <li>层级忽略 {@code ignoreDdlTablesByDb}：库名命中且表名命中该库下的表规则。</li>
     * </ol>
     */
    private boolean isDdlIgnored(String db, String table) {
        if (MatchPattern.matchesTable(ignoreDdlTables, db, table)) {
            return true;
        }
        if (commonDdlIgnoreTables != null && MatchPattern.matchesAny(commonDdlIgnoreTables, table)) {
            return true;
        }
        if (ignoreDdlTablesByDb != null) {
            for (DbTableIgnore e : ignoreDdlTablesByDb) {
                if (e == null || e.getDatabase() == null) {
                    continue;
                }
                if (MatchPattern.matchesAny(List.of(e.getDatabase()), db)
                        && e.getTables() != null && MatchPattern.matchesTable(e.getTables(), db, table)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 全量重置：停止 -> 清空目标库 -> 重新启动 */
    public void fullReset() {
        log.info("Full reset for {}", instanceKey);
        stop();
        watermarks.clear();
        knownSignatures.clear();
        knownTablesByDb.clear();
        start();
    }

    private void safeSync() {
        if (!running) {
            return;
        }
        try {
            doSync();
        } catch (Exception e) {
            log.error("SyncHostJob cycle failed for {}", instanceKey, e);
        }
    }

    private void doSync() {
        DatabaseConfig sourceHost = mapping.toSourceHostConfig();
        DatabaseConfig targetHost = mapping.toTargetHostConfig();

        List<String> dbs = metadataService.getAllUserDatabases(sourceHost, ignoreDatabases);
        for (String db : dbs) {
            if (!running) {
                return;
            }
            if (MatchPattern.matchesAny(ignoreDatabases, db)) {
                continue;
            }
            try {
                syncOneDatabase(db, sourceHost, targetHost);
            } catch (Exception e) {
                // 单库同步失败不中断其他库的同步
                log.error("Sync database {} failed, continuing to next db", db, e);
            }
        }
    }

    /** 同步单个数据库（含建库、表扫描、DDL检测、数据同步、删除对账、进度更新） */
    private void syncOneDatabase(String db, DatabaseConfig sourceHost, DatabaseConfig targetHost) {
        if (!metadataService.ensureTargetDatabase(targetHost, db)) {
            log.warn("Skip db {}: cannot ensure target database on {}", db, targetHost.getHost());
            return;
        }
        DatabaseConfig sourceDB = mapping.toSourceDB(db);
        DatabaseConfig targetDB = mapping.toTargetDB(db);
        DataBaseSinkFunction sink = getSink(db);

        List<String> tables = metadataService.listTables(sourceDB, db);
        Set<String> current = new HashSet<>(tables);
        int tableCount = 0;
        long sourceRows = 0;
        long targetRows = 0;

        log.info("Start syncing database {} ({} tables)", db, tables.size());

        for (String table : tables) {
            if (!running) {
                return;
            }
            if (isDmlIgnored(db, table)) {
                continue; // DML + DDL 全忽略
            }
            boolean ddlIgnored = isDdlIgnored(db, table);
            try {
                if (!ddlIgnored) {
                    detectAndApplyAlter(db, table, sourceDB, targetDB);
                }
                if (sink.ensureTable(table) == null) {
                    continue;
                }
                long synced = syncTable(sink, db, table, sourceDB, targetDB);
                sourceRows += synced;
                targetRows += countRows(targetDB, db, table);
                tableCount++;
            } catch (Exception e) {
                log.error("Sync table failed {}.{}", db, table, e);
            }
        }

        // DROP 检测：源库已不存在的表 -> 删除目标库对应表
        Set<String> prev = knownTablesByDb.get(db);
        if (prev != null) {
            for (String t : prev) {
                if (!current.contains(t) && !isDmlIgnored(db, t)) {
                    try {
                        sink.removeTable(t);
                        knownSignatures.remove(db + "." + t);
                        log.info("DDL DROP detected {}.{}, removed target table", db, t);
                    } catch (Exception e) {
                        log.warn("Failed to drop target table {}.{}", db, t, e);
                    }
                }
            }
        }
        knownTablesByDb.put(db, current);

        try {
            sink.flush(15000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        updateProgress(db, tableCount, sourceRows, targetRows);
        log.info("Finished syncing database {} ({} tables, src={} rows, tgt={} rows)", db, tableCount, sourceRows, targetRows);
    }

    /** ALTER 检测：源表结构变化则在目标端重建表并重置水位（忽略 DDL 的表跳过） */
    private void detectAndApplyAlter(String db, String table, DatabaseConfig sourceDB, DatabaseConfig targetDB) {
        TableInfo cur = metadataService.getTableInfo(sourceDB, db, table);
        if (cur == null) {
            return;
        }
        String sig = signature(cur);
        String prev = knownSignatures.get(db + "." + table);
        if (prev != null && !prev.equals(sig)) {
            metadataService.dropTargetTable(targetDB, table);
            metadataService.ensureTargetTable(sourceDB, targetDB, table);
            watermarks.remove(db + "." + table);
            log.info("DDL ALTER detected {}.{}, recreated target table", db, table);
        }
        knownSignatures.put(db + "." + table, sig);
    }

    /**
     * 单表增量/全量同步。
     * <ul>
     *   <li>有 update_time 且已有水位 → 增量：基于水位扫描，单批有 LIMIT 上限；</li>
     *   <li>否则 → 全量：<b>流式分页</b>扫描（{@link #streamScan}），逐页处理，内存占用恒定，
     *        即使千万级行也不会把整表一次性加载进 JVM 堆（修复百万数据 OOM / 丢失）。</li>
     * </ul>
     * 返回本周期扫描到的行数（用于进度展示）。
     */
    private long syncTable(DataBaseSinkFunction sink, String db, String table, DatabaseConfig sourceDB, DatabaseConfig targetDB) {
        TableInfo info = sink.tableInfoMap.get(table);
        if (info == null) {
            return 0;
        }
        boolean hasUpdateTime = info.getColumns().stream().anyMatch(c -> "update_time".equalsIgnoreCase(c));
        String wmKey = db + "." + table;
        LocalDateTime wm = watermarks.get(wmKey);
        boolean isFullSync = (wm == null);
        long t0 = System.currentTimeMillis();
        long scanned;
        if (hasUpdateTime && wm != null) {
            // 增量：单批有 LIMIT 上限，天然不撑爆内存
            List<Map<String, Object>> rows = selectChanged(sourceDB, db, table, info, wm);
            scanned = processRows(sink, db, table, info, rows, wmKey, hasUpdateTime);
        } else {
            // 全量：流式分页，内存恒定
            scanned = streamScan(sourceDB, db, table, info,
                    page -> processRows(sink, db, table, info, page, wmKey, hasUpdateTime));
        }
        long elapsed = System.currentTimeMillis() - t0;
        if (scanned > 0 || elapsed > 1000) {
            log.info("Synced {}.{}: {} rows in {}ms ({} sync)", db, table, scanned, elapsed,
                    isFullSync ? "full" : "incremental");
        }
        // 大表首次全量同步跳过 reconcileDeletes：百万级行的对账耗时极长，
        // 且首次全量同步刚写入所有数据，目标端不存在多余行。后续增量同步时会补对账。
        if (isFullSync && scanned > 100_000) {
            log.info("Skipping reconcileDeletes for {}.{} (large table first sync: {} rows)", db, table, scanned);
        } else {
            reconcileDeletes(sink, db, table, sourceDB, targetDB, info);
        }
        return scanned;
    }

    /** 单页行处理：更新水位 / 字段转换 / 入队 upsert；返回本页处理的行数 */
    private long processRows(DataBaseSinkFunction sink, String db, String table, TableInfo info,
                             List<Map<String, Object>> rows, String wmKey, boolean hasUpdateTime) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        for (Map<String, Object> row : rows) {
            if (hasUpdateTime) {
                Object ut = row.get("update_time");
                LocalDateTime lt = null;
                if (ut instanceof Timestamp) {
                    lt = ((Timestamp) ut).toLocalDateTime();
                } else if (ut instanceof LocalDateTime) {
                    lt = (LocalDateTime) ut;
                }
                if (lt != null) {
                    LocalDateTime wm = watermarks.get(wmKey);
                    if (wm == null || lt.isAfter(wm)) {
                        watermarks.put(wmKey, lt);
                    }
                }
            }
            applyTransforms(db, table, row, info.getPrimaryKeyColumns());
            sink.invoke(new RowChange(RowChange.OpType.INSERT, table, row, info.getPrimaryKeyColumns()));
        }
        return rows.size();
    }

    /**
     * 删除对账（最终一致性关键）：目标端存在但源端已不存在的行 → 发 DELETE。
     * <b>有界实现</b>：按主键分页拉取目标端 PK，每页分批回查源端是否存在，内存占用恒定，
     * 避免一次性把整表 PK 载入 JVM 堆（百万级行不 OOM）。
     */
    private void reconcileDeletes(DataBaseSinkFunction sink, String db, String table, DatabaseConfig sourceDB, DatabaseConfig targetDB, TableInfo info) {
        if (info.getPrimaryKeyColumns() == null || info.getPrimaryKeyColumns().isEmpty()) {
            return;
        }
        String pk = info.getPrimaryKeyColumns().get(0);
        int pageSize = props.getEngine().getBatchSize() * 5;
        Object lastKey = null;
        while (true) {
            List<Object> targetPage = selectPksPaged(targetDB, db, table, pk, pageSize, lastKey);
            if (targetPage.isEmpty()) {
                break;
            }
            // 分批回查源端：这些 PK 是否仍存在（IN 列表按 1000 分片，避免超参上限）
            Set<Object> present = new HashSet<>();
            for (int i = 0; i < targetPage.size(); i += 1000) {
                List<Object> chunk = targetPage.subList(i, Math.min(i + 1000, targetPage.size()));
                present.addAll(selectPksIn(sourceDB, db, table, pk, chunk));
            }
            for (Object tp : targetPage) {
                if (!present.contains(tp)) {
                    Map<String, Object> after = new LinkedHashMap<>();
                    after.put(pk, tp);
                    sink.invoke(new RowChange(RowChange.OpType.DELETE, table, after, info.getPrimaryKeyColumns()));
                }
            }
            if (targetPage.size() < pageSize) {
                break;
            }
            lastKey = targetPage.get(targetPage.size() - 1);
        }
    }

    /**
     * 流式全量扫描：按单列主键（keyset 分页）或 OFFSET 兜底分页，逐页交给 handler，
     * 内存占用恒定（每页约 batchSize*5 行），百万/千万级行也不会 OOM。返回扫描总行数。
     */
    private long streamScan(DatabaseConfig cfg, String db, String table, TableInfo info,
                            java.util.function.Consumer<List<Map<String, Object>>> pageHandler) {
        int pageSize = props.getEngine().getBatchSize() * 5;
        List<String> pks = info.getPrimaryKeyColumns();
        long total = 0;
        long lastLogTime = System.currentTimeMillis();
        if (pks != null && pks.size() == 1) {
            String pk = pks.get(0);
            Object lastKey = null;
            while (true) {
                String sql = "SELECT * FROM `" + db + "`.`" + table + "`"
                        + (lastKey == null ? "" : " WHERE `" + pk + "` > ?")
                        + " ORDER BY `" + pk + "` ASC LIMIT " + pageSize;
                List<Map<String, Object>> page = queryPage(cfg, sql, lastKey);
                if (page.isEmpty()) {
                    break;
                }
                pageHandler.accept(page);
                total += page.size();
                // 大表进度日志：每 10 万行或每 30 秒输出一次
                if (total % 100_000 < pageSize || System.currentTimeMillis() - lastLogTime > 30_000) {
                    log.info("streamScan progress {}.{}: {} rows scanned", db, table, total);
                    lastLogTime = System.currentTimeMillis();
                }
                if (page.size() < pageSize) {
                    break;
                }
                lastKey = page.get(page.size() - 1).get(pk);
                if (lastKey == null) {
                    break;
                }
            }
        } else {
            // 无单列主键：OFFSET 分页兜底（深翻页略慢，但内存仍恒定）
            long offset = 0;
            while (true) {
                String sql = "SELECT * FROM `" + db + "`.`" + table + "` LIMIT " + pageSize + " OFFSET " + offset;
                List<Map<String, Object>> page = queryRows(cfg, db, table, sql);
                if (page.isEmpty()) {
                    break;
                }
                pageHandler.accept(page);
                total += page.size();
                offset += page.size();
                if (page.size() < pageSize) {
                    break;
                }
            }
        }
        return total;
    }

    /** 参数化分页查询（用于 keyset 流式扫描） */
    private List<Map<String, Object>> queryPage(DatabaseConfig cfg, String sql, Object lastKey) {
        List<Map<String, Object>> result = new ArrayList<>();
        Connection conn = null;
        try {
            conn = connMgr.getConnection(cfg);
            try (java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                if (lastKey != null) {
                    ps.setObject(1, lastKey);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    result.addAll(extractRows(rs));
                }
            }
        } catch (SQLException e) {
            log.error("queryPage failed: {}", sql, e);
        } finally {
            closeQuietly(conn, cfg);
        }
        return result;
    }

    private List<Map<String, Object>> selectChanged(DatabaseConfig cfg, String db, String table, TableInfo info, LocalDateTime since) {
        String sql = "SELECT * FROM `" + db + "`.`" + table + "` WHERE `update_time` > ? ORDER BY `update_time` ASC LIMIT "
                + (props.getEngine().getBatchSize() * 5);
        List<Map<String, Object>> result = new ArrayList<>();
        Connection conn = null;
        try {
            conn = connMgr.getConnection(cfg);
            try (java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setObject(1, since);
                try (ResultSet rs = ps.executeQuery()) {
                    result.addAll(extractRows(rs));
                }
            }
        } catch (SQLException e) {
            log.error("selectChanged failed {}.{}", db, table, e);
        } finally {
            closeQuietly(conn, cfg);
        }
        return result;
    }

    private List<Map<String, Object>> queryRows(DatabaseConfig cfg, String db, String table, String sql) {
        List<Map<String, Object>> result = new ArrayList<>();
        Connection conn = null;
        try {
            conn = connMgr.getConnection(cfg);
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                result.addAll(extractRows(rs));
            }
        } catch (SQLException e) {
            log.error("queryRows failed: {}", sql, e);
        } finally {
            closeQuietly(conn, cfg);
        }
        return result;
    }

    private List<Map<String, Object>> extractRows(ResultSet rs) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        int colCount = rs.getMetaData().getColumnCount();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= colCount; i++) {
                row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
            }
            rows.add(row);
        }
        return rows;
    }

    /** 分页拉取目标端 PK（keyset 分页，内存恒定） */
    private List<Object> selectPksPaged(DatabaseConfig cfg, String db, String table, String pk, int pageSize, Object lastKey) {
        List<Object> pks = new ArrayList<>();
        Connection conn = null;
        try {
            conn = connMgr.getConnection(cfg);
            String sql = "SELECT `" + pk + "` FROM `" + db + "`.`" + table + "`"
                    + (lastKey == null ? "" : " WHERE `" + pk + "` > ?")
                    + " ORDER BY `" + pk + "` ASC LIMIT " + pageSize;
            try (java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                if (lastKey != null) {
                    ps.setObject(1, lastKey);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        pks.add(rs.getObject(1));
                    }
                }
            }
        } catch (SQLException e) {
            log.error("selectPksPaged failed {}.{}", db, table, e);
        } finally {
            closeQuietly(conn, cfg);
        }
        return pks;
    }

    /** 分批回查源端：给定 PK 集合里哪些仍存在（IN 列表按 1000 分片） */
    private List<Object> selectPksIn(DatabaseConfig cfg, String db, String table, String pk, List<Object> values) {
        List<Object> pks = new ArrayList<>();
        if (values == null || values.isEmpty()) {
            return pks;
        }
        Connection conn = null;
        try {
            conn = connMgr.getConnection(cfg);
            StringBuilder sb = new StringBuilder("SELECT `" + pk + "` FROM `" + db + "`.`" + table + "` WHERE `" + pk + "` IN (");
            for (int i = 0; i < values.size(); i++) {
                sb.append(i == 0 ? "?" : ",?");
            }
            sb.append(")");
            try (java.sql.PreparedStatement ps = conn.prepareStatement(sb.toString())) {
                for (int i = 0; i < values.size(); i++) {
                    ps.setObject(i + 1, values.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        pks.add(rs.getObject(1));
                    }
                }
            }
        } catch (SQLException e) {
            log.error("selectPksIn failed {}.{}", db, table, e);
        } finally {
            closeQuietly(conn, cfg);
        }
        return pks;
    }

    private long countRows(DatabaseConfig cfg, String db, String table) {
        Connection conn = null;
        try {
            conn = connMgr.getConnection(cfg);
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(1) FROM `" + db + "`.`" + table + "`")) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        } catch (SQLException e) {
            log.error("countRows failed {}.{}", db, table, e);
        } finally {
            closeQuietly(conn, cfg);
        }
        return -1;
    }

    /** 应用字段转换规则（跳过主键列，避免破坏幂等 upsert） */
    private void applyTransforms(String db, String table, Map<String, Object> row, List<String> pkCols) {
        if (transformRules.isEmpty() || row == null) {
            return;
        }
        Set<String> pkSet = pkCols == null ? java.util.Collections.emptySet() : new HashSet<>(pkCols);
        for (Map.Entry<String, Object> e : row.entrySet()) {
            String col = e.getKey();
            if (pkSet.contains(col)) {
                continue;
            }
            Object val = e.getValue();
            if (val == null) {
                continue;
            }
            String strVal = String.valueOf(val);
            for (com.example.flinkcdcsync.bean.TransformRule rule : transformRules) {
                if (rule.matches(db, table, col) && strVal.equals(rule.getSourceValue())) {
                    e.setValue(rule.getTargetValue());
                    log.debug("Transform applied {}.{}.{} : '{}' -> '{}'", db, table, col, rule.getSourceValue(), rule.getTargetValue());
                    break;
                }
            }
        }
    }

    private void updateProgress(String db, int tableCount, long sourceRows, long targetRows) {
        String key = mapping.getSourceHost() + "|" + db;
        SyncProgress p = progressMap.computeIfAbsent(key, k -> {
            SyncProgress sp = new SyncProgress();
            sp.setSourceIp(mapping.getSourceHost());
            sp.setSourceDbName(db);
            sp.setTargetIp(mapping.getTargetHost());
            sp.setTargetDbName(db);
            sp.setSyncStartTime(LocalDateTime.now());
            sp.setState(SyncStateEnum.FULL_SYNC.getCode());
            return sp;
        });
        p.setSourceDbName(db);
        p.setTargetDbName(db);
        p.setSourceIp(mapping.getSourceHost());
        p.setTargetIp(mapping.getTargetHost());
        p.setState(SyncStateEnum.SYNCING.getCode());
        p.setSourceBinlogFile("LOCAL-SYNC");
        p.setSourceBinlogTime(LocalDateTime.now());
        p.setSyncBinlogFile("LOCAL-SYNC");
        p.setSyncBinlogTime(LocalDateTime.now());
        p.setDeviationTimes(0L);
        p.setDeviationStatus(DelivationStatusEnum.NORMAL.getCode());
        p.setSuspensionReason(null);
        p.setProcessingMethod(null);
        p.setUpdateTime(LocalDateTime.now());
        p.setProcessingMethod("tables=" + tableCount + ",src=" + sourceRows + ",tgt=" + targetRows);
    }

    private String signature(TableInfo info) {
        return String.join(",", info.getColumns()) + "#" + String.join(",", info.getPrimaryKeyColumns());
    }

    private void closeQuietly(Connection conn, DatabaseConfig cfg) {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException ignore) {
            }
            connMgr.releaseConnection();
        }
    }

    public String getInstanceKey() {
        return instanceKey;
    }
}
