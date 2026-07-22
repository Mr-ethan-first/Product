package com.example.remotedatasync.manager;

import com.example.remotedatasync.bean.DatabaseConfig;
import com.example.remotedatasync.bean.DatabaseMapping;
import com.example.remotedatasync.common.ConnectionTestResult;
import com.example.remotedatasync.config.DRPlatformProperties;
import com.example.remotedatasync.dto.DatabaseMappingVO;
import com.example.remotedatasync.dto.MappingBatchResult;
import com.example.remotedatasync.dto.SyncProgressQuery;
import com.example.remotedatasync.po.SyncProgress;
import com.example.remotedatasync.service.DatabaseMetadataService;
import com.example.remotedatasync.common.CryptoUtil;
import com.example.remotedatasync.sync.SyncHostJob;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 同步服务总控：负责启动 / 停止 / 重启主机对同步任务，维护内存进度与运行作业。
 * 支持页面动态新增 / 移除同步主机对（并持久化到本地文件，重启后自动恢复）。
 * <p>
 * 模型为<b>主机对</b>（源主机 -> 目标主机，1:1）；每个主机对启动一个 SyncHostJob，
 * 自动发现并同步该源主机下所有用户库与表（忽略项除外）。
 * </p>
 *
 * @author 50707
 */
@Slf4j
@Component
public class DatabaseSyncManager {

    /** 内存同步进度缓存：sourceHost|sourceDbName -> SyncProgress */
    public static final Map<String, SyncProgress> syncProgressMap = new ConcurrentHashMap<>();

    /** 动态映射持久化文件（页面新增的映射，重启后自动加载） */
    private static final String DYNAMIC_MAPPINGS_FILE = "DRPlatform-dynamic-mappings.json";

    private final DRPlatformProperties properties;
    private final DynamicShardedConnectionManager connMgr;
    private final GroupedExecutorManager executorManager;
    private final TableLevelQueueManager queueManager;
    private final DatabaseMetadataService metadataService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 运行中的同步作业：instanceKey(主机对) -> SyncHostJob */
    private final Map<String, SyncHostJob> runningJobs = new ConcurrentHashMap<>();

    /** 通过页面动态新增的映射 key 集合（用于持久化与来源标记） */
    private final Set<String> dynamicKeys = ConcurrentHashMap.newKeySet();

    /** 增删映射时的互斥锁 */
    private final Object mappingsLock = new Object();

    public DatabaseSyncManager(DRPlatformProperties properties,
                               DynamicShardedConnectionManager connMgr,
                               GroupedExecutorManager executorManager,
                               TableLevelQueueManager queueManager,
                               DatabaseMetadataService metadataService) {
        this.properties = properties;
        this.connMgr = connMgr;
        this.executorManager = executorManager;
        this.queueManager = queueManager;
        this.metadataService = metadataService;
    }

    @PostConstruct
    public void startSyncTask() {
        if (!properties.isStandbyMode()) {
            log.warn("This server is not a disaster recovery center, sync service will not start.");
            return;
        }
        loadDynamicMappings();

        List<DatabaseMapping> mappings = properties.getMappings();
        if (mappings == null || mappings.isEmpty()) {
            log.error("Database mapping configuration is empty, please check backup configuration.");
            return;
        }
        log.info("Starting sync task, parsed host-pair mapping count: {}", mappings.size());
        Set<String> started = new HashSet<>();
        for (DatabaseMapping mapping : mappings) {
            String key = mapping.getInstanceKey();
            if (!started.add(key)) {
                log.warn("Duplicate host-pair mapping skipped: {}", key);
                continue;
            }
            try {
                startHostJob(mapping);
            } catch (Exception e) {
                log.error("Failed to start sync for mapping {}", key, e);
            }
        }
        log.info("Sync task started, running jobs: {}", runningJobs.size());
    }

    private void startHostJob(DatabaseMapping mapping) {
        SyncHostJob job = new SyncHostJob(mapping, properties, connMgr, executorManager,
                queueManager, metadataService, syncProgressMap);
        job.start();
        runningJobs.put(job.getInstanceKey(), job);
    }

    /** 重新同步指定主机对（全量重置） */
    public Map<String, List<SyncProgressQuery>> resyncDatabases(List<SyncProgressQuery> databaseList) {
        Map<String, List<SyncProgressQuery>> result = new HashMap<>();
        List<SyncProgressQuery> success = new ArrayList<>();
        List<SyncProgressQuery> failed = new ArrayList<>();
        for (SyncProgressQuery q : databaseList) {
            DatabaseMapping mapping = findMappingByIp(q.getIp());
            if (mapping == null) {
                log.warn("No mapping for ip={}", q.getIp());
                failed.add(q);
                continue;
            }
            SyncHostJob job = runningJobs.get(mapping.getInstanceKey());
            try {
                if (job == null) {
                    startHostJob(mapping);
                } else {
                    job.fullReset();
                }
                success.add(q);
            } catch (Exception e) {
                log.error("Resync failed for {}", mapping.getInstanceKey(), e);
                failed.add(q);
            }
        }
        if (!success.isEmpty()) {
            result.put("success", success);
        }
        if (!failed.isEmpty()) {
            result.put("failed", failed);
        }
        return result;
    }

    /** 精确查找主机对映射（按源/目标 IP 匹配；dbName 仅作兼容，不再用于匹配） */
    public DatabaseMapping findMapping(String ip, String dbName) {
        return findMappingByIp(ip);
    }

    /** 在锁内对 mappings 做快照，避免读路径与写路径并发导致 ConcurrentModificationException。 */
    private List<DatabaseMapping> snapshotMappings() {
        synchronized (mappingsLock) {
            return new ArrayList<>(properties.getMappings());
        }
    }

    public DatabaseMapping findMappingByIp(String ip) {
        if (ip == null) {
            return null;
        }
        return snapshotMappings().stream()
                .filter(m -> ip.equals(m.getSourceHost()) || ip.equals(m.getTargetHost()))
                .findFirst().orElse(null);
    }

    private DatabaseMapping findByKey(String instanceKey) {
        if (instanceKey == null || properties.getMappings() == null) {
            return null;
        }
        return properties.getMappings().stream()
                .filter(m -> instanceKey.equals(m.getInstanceKey()))
                .findFirst().orElse(null);
    }

    /**
     * 页面动态新增同步主机对：去重 + 启动同步作业 + 持久化。
     */
    public String addMapping(DatabaseMapping mapping) {
        String key = mapping.getInstanceKey();
        synchronized (mappingsLock) {
            if (findByKey(key) != null) {
                throw new IllegalStateException("MAPPING_ALREADY_EXISTS:" + key);
            }
            startHostJob(mapping);
            if (!containsByKey(key)) {
                properties.getMappings().add(mapping);
            }
            dynamicKeys.add(key);
            persistDynamicMappings();
            log.info("Added dynamic host-pair mapping {} and started sync job", key);
            return key;
        }
    }

    /**
     * 批量新增同步主机对：逐个去重 + 启动 + 持久化。
     * <p>
     * 新增的 mapping 自动绑定当前操作用户 ID（从 UserContext 获取），
     * 实现配置按用户区分。userId 为 null 的 mapping 对所有用户可见。
     * </p>
     */
    public MappingBatchResult addMappings(List<DatabaseMapping> mappings) {
        MappingBatchResult result = new MappingBatchResult();
        if (mappings == null || mappings.isEmpty()) {
            return result;
        }
        // 绑定当前操作用户 ID
        Long currentUserId = com.example.remotedatasync.common.UserContext.getUserId();
        synchronized (mappingsLock) {
            for (DatabaseMapping mapping : mappings) {
                String key = mapping.getInstanceKey();
                if (findByKey(key) != null) {
                    result.getSkipped().add(key);
                    continue;
                }
                try {
                    // 设置所属用户 ID（实现配置按用户区分）
                    if (mapping.getUserId() == null) {
                        mapping.setUserId(currentUserId);
                    }
                    startHostJob(mapping);
                    if (!containsByKey(key)) {
                        properties.getMappings().add(mapping);
                    }
                    dynamicKeys.add(key);
                    result.getCreated().add(key);
                } catch (Exception e) {
                    log.error("Failed to add mapping {}", key, e);
                    result.getFailed().add(key + ": " + e.getMessage());
                }
            }
            persistDynamicMappings();
        }
        log.info("Batch add mappings done: created={}, skipped={}, failed={}, userId={}",
                result.getCreated().size(), result.getSkipped().size(), result.getFailed().size(), currentUserId);
        return result;
    }

    private boolean containsByKey(String instanceKey) {
        return properties.getMappings().stream().anyMatch(m -> instanceKey.equals(m.getInstanceKey()));
    }

    /** 移除指定主机对：停止作业 + 关闭连接池 + 从列表移除 + 持久化 */
    public void removeMapping(String instanceKey) {
        synchronized (mappingsLock) {
            DatabaseMapping mapping = findByKey(instanceKey);
            if (mapping == null) {
                throw new IllegalStateException("MAPPING_NOT_FOUND:" + instanceKey);
            }
            SyncHostJob job = runningJobs.remove(instanceKey);
            if (job != null) {
                job.stop();
            }
            // 修复连接池泄漏：按主机清理所有分片池（含按库名创建的池），而非仅清理空库名的池
            connMgr.removeConnectionPoolsByHost(mapping.getSourceHost(), mapping.getSourcePort());
            connMgr.removeConnectionPoolsByHost(mapping.getTargetHost(), mapping.getTargetPort());
            properties.getMappings().removeIf(m -> instanceKey.equals(m.getInstanceKey()));
            dynamicKeys.remove(instanceKey);
            persistDynamicMappings();
            log.info("Removed host-pair mapping {}", instanceKey);
        }
    }

    /**
     * 重新加载指定主机对的配置并重建同步作业（配置热生效）。
     * <p>
     * 用于修改 ignoreDatabases / ignoreTables / transformRules 等配置后的热生效：
     * 停止旧作业 → 清理旧连接池 → 用最新 mapping 对象创建新 SyncHostJob 并启动。
     * 新作业的 watermarks / knownSignatures 从空白开始（等价于 fullReset 的效果），
     * 下一轮扫描自动全量重扫并重建水位，依靠幂等 upsert 保证数据最终一致。
     * </p>
     *
     * @param instanceKey 主机对实例 key（sourceHost->targetHost）
     * @return 重建是否成功
     */
    public boolean reloadMapping(String instanceKey) {
        synchronized (mappingsLock) {
            DatabaseMapping mapping = findByKey(instanceKey);
            if (mapping == null) {
                throw new IllegalStateException("MAPPING_NOT_FOUND:" + instanceKey);
            }
            // 1. 停止旧作业
            SyncHostJob oldJob = runningJobs.remove(instanceKey);
            if (oldJob != null) {
                oldJob.stop();
            }
            // 2. 清理旧连接池（含按库名分片的池）
            connMgr.removeConnectionPoolsByHost(mapping.getSourceHost(), mapping.getSourcePort());
            connMgr.removeConnectionPoolsByHost(mapping.getTargetHost(), mapping.getTargetPort());
            // 3. 用最新 mapping 对象重建作业（ignore 等配置从 mapping 重新读取）
            try {
                startHostJob(mapping);
                log.info("Reloaded host-pair mapping {} with latest config", instanceKey);
                return true;
            } catch (Exception e) {
                log.error("Failed to reload mapping {}", instanceKey, e);
                return false;
            }
        }
    }

    /**
     * 运行时更新指定主机对的配置字段（ignoreDatabases / ignoreTables / transformRules 等）。
     * <p>
     * 仅更新内存中的 mapping 对象，不持久化到 yml。更新后需调用
     * {@link #reloadMapping(String)} 重建作业才能让新配置对同步引擎生效。
     * 动态新增的 mapping 会同时持久化到 JSON 文件。
     * </p>
     *
     * @param instanceKey 主机对实例 key
     * @param configUpdate 包含待更新字段的 Map（支持 ignoreDatabases, ignoreTables,
     *                     ignoreDdlTables, commonIgnoreTables, commonDdlIgnoreTables,
     *                     transformRules, ignoreTablesByDb, ignoreDdlTablesByDb）
     * @return 更新是否成功
     */
    @SuppressWarnings("unchecked")
    public boolean updateMappingConfig(String instanceKey, Map<String, Object> configUpdate) {
        synchronized (mappingsLock) {
            DatabaseMapping mapping = findByKey(instanceKey);
            if (mapping == null) {
                throw new IllegalStateException("MAPPING_NOT_FOUND:" + instanceKey);
            }
            boolean changed = false;
            // 逐字段更新（仅更新提供的字段，未提供的保持不变）
            if (configUpdate.containsKey("ignoreDatabases")) {
                mapping.setIgnoreDatabases(toStringList(configUpdate.get("ignoreDatabases")));
                changed = true;
            }
            if (configUpdate.containsKey("ignoreTables")) {
                mapping.setIgnoreTables(toStringList(configUpdate.get("ignoreTables")));
                changed = true;
            }
            if (configUpdate.containsKey("ignoreDdlTables")) {
                mapping.setIgnoreDdlTables(toStringList(configUpdate.get("ignoreDdlTables")));
                changed = true;
            }
            if (configUpdate.containsKey("commonIgnoreTables")) {
                mapping.setCommonIgnoreTables(toStringList(configUpdate.get("commonIgnoreTables")));
                changed = true;
            }
            if (configUpdate.containsKey("commonDdlIgnoreTables")) {
                mapping.setCommonDdlIgnoreTables(toStringList(configUpdate.get("commonDdlIgnoreTables")));
                changed = true;
            }
            if (configUpdate.containsKey("transformRules")) {
                Object val = configUpdate.get("transformRules");
                if (val instanceof List) {
                    List<com.example.remotedatasync.bean.TransformRule> rules = new ArrayList<>();
                    for (Object item : (List<?>) val) {
                        if (item instanceof Map) {
                            Map<String, Object> m = (Map<String, Object>) item;
                            com.example.remotedatasync.bean.TransformRule rule = new com.example.remotedatasync.bean.TransformRule();
                            if (m.containsKey("dbName")) rule.setDbName((String) m.get("dbName"));
                            if (m.containsKey("tableName")) rule.setTableName((String) m.get("tableName"));
                            if (m.containsKey("fieldName")) rule.setFieldName((String) m.get("fieldName"));
                            if (m.containsKey("sourceValue")) rule.setSourceValue((String) m.get("sourceValue"));
                            if (m.containsKey("targetValue")) rule.setTargetValue((String) m.get("targetValue"));
                            rules.add(rule);
                        }
                    }
                    mapping.setTransformRules(rules);
                    changed = true;
                }
            }
            if (configUpdate.containsKey("ignoreTablesByDb")) {
                Object val = configUpdate.get("ignoreTablesByDb");
                if (val instanceof List) {
                    List<com.example.remotedatasync.bean.DbTableIgnore> ignores = new ArrayList<>();
                    for (Object item : (List<?>) val) {
                        if (item instanceof Map) {
                            Map<String, Object> m = (Map<String, Object>) item;
                            com.example.remotedatasync.bean.DbTableIgnore ignore = new com.example.remotedatasync.bean.DbTableIgnore();
                            if (m.containsKey("database")) ignore.setDatabase((String) m.get("database"));
                            if (m.containsKey("tables")) ignore.setTables(toStringList(m.get("tables")));
                            ignores.add(ignore);
                        }
                    }
                    mapping.setIgnoreTablesByDb(ignores);
                    changed = true;
                }
            }
            if (configUpdate.containsKey("ignoreDdlTablesByDb")) {
                Object val = configUpdate.get("ignoreDdlTablesByDb");
                if (val instanceof List) {
                    List<com.example.remotedatasync.bean.DbTableIgnore> ignores = new ArrayList<>();
                    for (Object item : (List<?>) val) {
                        if (item instanceof Map) {
                            Map<String, Object> m = (Map<String, Object>) item;
                            com.example.remotedatasync.bean.DbTableIgnore ignore = new com.example.remotedatasync.bean.DbTableIgnore();
                            if (m.containsKey("database")) ignore.setDatabase((String) m.get("database"));
                            if (m.containsKey("tables")) ignore.setTables(toStringList(m.get("tables")));
                            ignores.add(ignore);
                        }
                    }
                    mapping.setIgnoreDdlTablesByDb(ignores);
                    changed = true;
                }
            }
            // 动态 mapping 持久化到 JSON 文件
            if (changed && dynamicKeys.contains(instanceKey)) {
                persistDynamicMappings();
            }
            log.info("Updated mapping config for {} (changed={})", instanceKey, changed);
            return changed;
        }
    }

    /** 将 Object 转为 List<String>，兼容 List<String> 和 List<Object> */
    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object val) {
        if (val == null) {
            return new ArrayList<>();
        }
        if (val instanceof List) {
            List<String> result = new ArrayList<>();
            for (Object item : (List<?>) val) {
                result.add(String.valueOf(item));
            }
            return result;
        }
        return new ArrayList<>();
    }

    /** 连接测试透传：返回源/目标两端的测试结果（服务端级，不依赖具体库名） */
    public ConnectionTestResult testSource(DatabaseMapping mapping) {
        return mapping.toSourceHostConfig().testServerConnection();
    }

    public ConnectionTestResult testTarget(DatabaseMapping mapping) {
        return mapping.toTargetHostConfig().testServerConnection();
    }

    /** 按源主机连接信息列出可同步的数据库（排除系统库与忽略库） */
    public List<String> listSourceDatabases(DatabaseConfig cfg) {
        return metadataService.getAllUserDatabases(cfg, List.of());
    }

    /** 当前所有映射的展示对象（密码脱敏，含运行状态与来源） */
    public List<DatabaseMappingVO> listMappingsVO() {
        return listMappingsVO(null);
    }

    /**
     * 按用户过滤的映射展示对象。
     * <p>
     * 返回 userId 为 null（全局/yml 配置）+ userId 等于指定用户的映射。
     * 实现配置按用户区分：用户只能看到全局映射和自己创建的映射。
     * </p>
     *
     * @param userId 当前登录用户 ID（null 表示不过滤，返回全部）
     */
    public List<DatabaseMappingVO> listMappingsVO(Long userId) {
        List<DatabaseMapping> mappings = snapshotMappings();
        if (mappings.isEmpty()) {
            return new ArrayList<>();
        }
        return mappings.stream()
                .filter(m -> userId == null || m.getUserId() == null || userId.equals(m.getUserId()))
                .map(m -> DatabaseMappingVO.from(m, runningJobs.containsKey(m.getInstanceKey()),
                        dynamicKeys.contains(m.getInstanceKey()) ? "dynamic" : "yaml"))
                .collect(Collectors.toList());
    }

    /** 根据 IP 获取该 IP 下的用户数据库列表（去重） */
    public List<String> getDatabasesByIp(String ips) {
        List<String> result = new ArrayList<>();
        List<String> ipList = new ArrayList<>();
        if (ips != null && !ips.isEmpty()) {
            for (String s : ips.split(",")) {
                ipList.add(s.trim());
            }
        }
        for (DatabaseMapping mapping : snapshotMappings()) {
            if (!ipList.isEmpty() && !ipList.contains(mapping.getSourceHost()) && !ipList.contains(mapping.getTargetHost())) {
                continue;
            }
            List<String> dbs = metadataService.getAllUserDatabases(mapping.toSourceHostConfig(), mapping.getIgnoreDatabases());
            result.addAll(dbs);
        }
        return result.stream().distinct().collect(Collectors.toList());
    }

    public List<DatabaseMapping> getMappings() {
        return snapshotMappings();
    }

    public int runningJobCount() {
        return runningJobs.size();
    }

    /** 启动时加载页面动态新增的映射（重启恢复），并解密其中的数据库密码。 */
    private void loadDynamicMappings() {
        File f = new File(DYNAMIC_MAPPINGS_FILE);
        if (!f.exists()) {
            return;
        }
        try {
            List<DatabaseMapping> loaded = objectMapper.readValue(f, new TypeReference<List<DatabaseMapping>>() {});
            for (DatabaseMapping m : loaded) {
                // 解密持久化的密码（历史明文值自动兼容）
                m.setSourcePassword(CryptoUtil.decrypt(m.getSourcePassword()));
                m.setTargetPassword(CryptoUtil.decrypt(m.getTargetPassword()));
                if (findByKey(m.getInstanceKey()) == null) {
                    properties.getMappings().add(m);
                    dynamicKeys.add(m.getInstanceKey());
                }
            }
            log.info("Loaded {} dynamic mapping(s) from {}", loaded.size(), f.getAbsolutePath());
        } catch (Exception e) {
            log.warn("Failed to load dynamic mappings from {}: {}", f.getAbsolutePath(), e.getMessage());
        }
    }

    /** 持久化页面动态新增的映射到本地 JSON 文件（密码加密，避免明文落盘）。 */
    private void persistDynamicMappings() {
        try {
            List<DatabaseMapping> toSave = properties.getMappings().stream()
                    .filter(m -> dynamicKeys.contains(m.getInstanceKey()))
                    .map(m -> {
                        // 写盘前克隆并加密密码字段；内存中的真实密码保持不变（同步连接需要）
                        DatabaseMapping copy = objectMapper.convertValue(m, DatabaseMapping.class);
                        copy.setSourcePassword(CryptoUtil.encrypt(m.getSourcePassword()));
                        copy.setTargetPassword(CryptoUtil.encrypt(m.getTargetPassword()));
                        return copy;
                    })
                    .collect(Collectors.toList());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(DYNAMIC_MAPPINGS_FILE), toSave);
            log.debug("Persisted {} dynamic mapping(s) to {}", toSave.size(), DYNAMIC_MAPPINGS_FILE);
        } catch (Exception e) {
            log.warn("Failed to persist dynamic mappings: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        runningJobs.values().forEach(SyncHostJob::stop);
        runningJobs.clear();
        queueManager.TABLE_QUEUES.forEach((k, q) -> q.clear());
        queueManager.TABLE_QUEUES.clear();
        executorManager.shutdownAll();
        connMgr.shutdownAll();
        log.info("DatabaseSyncManager shut down");
    }
}
