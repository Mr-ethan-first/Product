package com.example.flinkcdcsync.manager;

import com.example.flinkcdcsync.bean.DatabaseConfig;
import com.example.flinkcdcsync.config.GeoDRSyncProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 动态分片连接管理器：基于 (host|db) 维护 Hikari 连接池，并通过全局信号量控制最大连接数，
 * 防止目标库连接数超限。
 *
 * @author 50707
 */
@Slf4j
@Component
public class DynamicShardedConnectionManager {

    private final Map<String, HikariDataSource> poolMap = new ConcurrentHashMap<>();
    private final GeoDRSyncProperties properties;
    private final Semaphore globalSemaphore;
    private final AtomicInteger usingConnections = new AtomicInteger(0);

    public DynamicShardedConnectionManager(GeoDRSyncProperties properties) {
        this.properties = properties;
        int maxGlobal = properties.getConnection() != null ? properties.getConnection().getMaxGlobalConnections() : 200;
        this.globalSemaphore = new Semaphore(Math.max(1, maxGlobal));
    }

    private String poolKey(DatabaseConfig cfg) {
        return cfg.getHost() + ":" + cfg.getPort() + "/" + cfg.getDatabaseName();
    }

    public DataSource getDataSource(DatabaseConfig cfg) {
        return poolMap.computeIfAbsent(poolKey(cfg), k -> {
            HikariConfig hc = new HikariConfig();
            hc.setJdbcUrl(cfg.getDBURL());
            hc.setUsername(cfg.getUsername());
            hc.setPassword(cfg.getPassword());
            int maxPool = properties.getConnection() != null ? properties.getConnection().getMaxPoolSize() : 32;
            hc.setMaximumPoolSize(Math.max(2, maxPool));
            hc.setMinimumIdle(2);
            hc.setPoolName("GeoDRSync-" + k);
            hc.setConnectionTimeout(30000);
            hc.setIdleTimeout(600000);
            hc.setMaxLifetime(1800000);
            log.info("Created connection pool for {}", maskSensitive(k, cfg));
            return new HikariDataSource(hc);
        });
    }

    /** 获取一个连接（受全局信号量约束）。连接使用方必须调用 {@link #releaseConnection()} 释放信号量。 */
    public Connection getConnection(DatabaseConfig cfg) throws SQLException {
        try {
            globalSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for global connection permit", e);
        }
        try {
            Connection conn = getDataSource(cfg).getConnection();
            usingConnections.incrementAndGet();
            return conn;
        } catch (SQLException e) {
            globalSemaphore.release();
            throw e;
        }
    }

    public void releaseConnection() {
        globalSemaphore.release();
        usingConnections.decrementAndGet();
    }

    public void removeConnectionPool(DatabaseConfig cfg) {
        HikariDataSource ds = poolMap.remove(poolKey(cfg));
        if (ds != null) {
            try {
                ds.close();
                log.info("Closed connection pool for {}", poolKey(cfg));
            } catch (Exception e) {
                log.warn("Error closing pool {}", poolKey(cfg), e);
            }
        }
    }

    /**
     * 移除指定主机（host:port）下的所有连接池（含主机级空库池与按库名分片的池）。
     * <p>
     * 修复连接池泄漏：{@link #removeConnectionPool(DatabaseConfig)} 仅能移除精确匹配
     * poolKey 的池，而同步过程中会按真实库名惰性创建 host:port/dbName 的分片池。
     * 调用 removeConnectionPool(toSourceHostConfig()) 时 dbName 为空，只能关掉
     * host:port/ 这个 key，真正在用的 host:port/具体库名 的池不会被清理，
     * 导致 mapping 移除后连接池长期驻留，占用 MySQL 连接数。
     * </p>
     *
     * @param host 主机 IP
     * @param port 端口
     * @return 实际关闭的连接池数量
     */
    public int removeConnectionPoolsByHost(String host, int port) {
        String prefix = host + ":" + port + "/";
        int closed = 0;
        // 遍历所有池 key，移除以 host:port/ 开头的（含空库名和具体库名）
        for (String key : new java.util.ArrayList<>(poolMap.keySet())) {
            if (key.startsWith(prefix)) {
                HikariDataSource ds = poolMap.remove(key);
                if (ds != null) {
                    try {
                        ds.close();
                        closed++;
                        log.info("Closed connection pool for {} during host-level cleanup", key);
                    } catch (Exception e) {
                        log.warn("Error closing pool {} during host-level cleanup", key, e);
                    }
                }
            }
        }
        if (closed > 0) {
            log.info("Closed {} connection pool(s) for {}:{}", closed, host, port);
        }
        return closed;
    }

    public void shutdownAll() {
        poolMap.forEach((k, ds) -> {
            try {
                ds.close();
            } catch (Exception e) {
                log.warn("Error closing pool {}", k, e);
            }
        });
        poolMap.clear();
        log.info("All connection pools shut down");
    }

    /** 打印连接池统计，便于排查连接泄漏 */
    public String getPoolStatistics() {
        StringBuilder sb = new StringBuilder("poolStats[");
        poolMap.forEach((k, ds) -> sb.append(k).append("=").append(ds.getHikariPoolMXBean().getActiveConnections())
                .append("/").append(ds.getHikariPoolMXBean().getTotalConnections()).append("; "));
        sb.append("globalUsed=").append(usingConnections.get()).append("/").append(properties.getConnection().getMaxGlobalConnections());
        sb.append("]");
        return sb.toString();
    }

    private String maskSensitive(String key, DatabaseConfig cfg) {
        // 连接信息脱敏：不打印密码
        return key;
    }
}
