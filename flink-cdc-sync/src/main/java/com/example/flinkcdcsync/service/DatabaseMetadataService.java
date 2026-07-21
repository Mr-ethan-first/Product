package com.example.flinkcdcsync.service;

import com.example.flinkcdcsync.bean.DatabaseConfig;
import com.example.flinkcdcsync.bean.TableInfo;
import com.example.flinkcdcsync.manager.DynamicShardedConnectionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.example.flinkcdcsync.util.MatchPattern;

/**
 * 数据库元数据服务：用户库发现、表结构提取、目标库建表（镜像源库 DDL）。
 *
 * @author 50707
 */
@Slf4j
@Component
public class DatabaseMetadataService {

    private static final Set<String> SYSTEM_SCHEMAS = new HashSet<>(Arrays.asList(
            "information_schema", "mysql", "performance_schema", "sys", "geodrsync"));

    private final DynamicShardedConnectionManager connMgr;

    public DatabaseMetadataService(DynamicShardedConnectionManager connMgr) {
        this.connMgr = connMgr;
    }

    /** 获取所有用户数据库（排除系统库与配置忽略库；ignorePatterns 支持正则/通配，见 MatchPattern） */
    public List<String> getAllUserDatabases(DatabaseConfig cfg, List<String> ignorePatterns) {
        List<String> result = new ArrayList<>();
        String sql = "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA";
        Connection conn = null;
        try {
            conn = connMgr.getConnection(cfg);
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    String db = rs.getString(1);
                    if (SYSTEM_SCHEMAS.contains(db.toLowerCase())
                            || MatchPattern.matchesAny(ignorePatterns, db)) {
                        continue;
                    }
                    result.add(db);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to list databases for {}", cfg.getHost(), e);
        } finally {
            closeQuietly(conn, cfg);
        }
        return result;
    }

    /** 目标主机上是否存在指定库 */
    public boolean databaseExists(DatabaseConfig cfg, String dbName) {
        Connection conn = null;
        try {
            conn = connMgr.getConnection(cfg);
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='"
                                 + dbName.replace("'", "''") + "'")) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        } finally {
            closeQuietly(conn, cfg);
        }
    }

    /** 确保目标主机上存在该库（不存在则创建），返回是否成功 */
    public boolean ensureTargetDatabase(DatabaseConfig targetHost, String dbName) {
        if (databaseExists(targetHost, dbName)) {
            return true;
        }
        Connection conn = null;
        try {
            conn = connMgr.getConnection(targetHost);
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE DATABASE IF NOT EXISTS `" + dbName.replace("`", "") + "` DEFAULT CHARACTER SET utf8mb4");
                log.info("Created target database {} at {}", dbName, targetHost.getHost());
                return true;
            }
        } catch (SQLException e) {
            log.error("Failed to create target database {} at {}", dbName, targetHost.getHost(), e);
            return false;
        } finally {
            closeQuietly(conn, targetHost);
        }
    }

    /** 提取表结构信息（列 + 主键） */
    public TableInfo getTableInfo(DatabaseConfig cfg, String dbName, String tableName) {
        List<String> columns = new ArrayList<>();
        List<String> pk = new ArrayList<>();
        Connection conn = null;
        try {
            conn = connMgr.getConnection(cfg);
            // 列
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                                 + "WHERE TABLE_SCHEMA='" + dbName + "' AND TABLE_NAME='" + tableName + "' ORDER BY ORDINAL_POSITION")) {
                while (rs.next()) {
                    columns.add(rs.getString(1));
                }
            }
            // 主键
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE "
                                 + "WHERE TABLE_SCHEMA='" + dbName + "' AND TABLE_NAME='" + tableName
                                 + "' AND CONSTRAINT_NAME='PRIMARY' ORDER BY ORDINAL_POSITION")) {
                while (rs.next()) {
                    pk.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get table info {}.{}", dbName, tableName, e);
        } finally {
            closeQuietly(conn, cfg);
        }
        if (columns.isEmpty()) {
            return null;
        }
        return new TableInfo(tableName, pk, columns);
    }

    /** 获取源表建表语句（去掉库限定符，便于在目标库执行） */
    public String getCreateTableSql(DatabaseConfig cfg, String tableName) {
        Connection conn = null;
        try {
            conn = connMgr.getConnection(cfg);
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SHOW CREATE TABLE `" + tableName + "`")) {
                if (rs.next()) {
                    String ddl = rs.getString(2);
                    // 去掉库限定符 `db`.`
                    ddl = ddl.replaceAll("`[^`]+`\\s*\\.", "");
                    ddl = ddl.replaceFirst("(?i)^CREATE TABLE", "CREATE TABLE IF NOT EXISTS");
                    ddl = sanitizeDdlForPortability(ddl);
                    return ddl;
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get create table for {}", tableName, e);
        } finally {
            closeQuietly(conn, cfg);
        }
        return null;
    }

    /**
     * DDL 跨版本可移植性归一化：灾备目标库版本可能低于生产源库（如源 MySQL 8.0、目标 5.7）。
     * MySQL 8.0 默认排序规则 utf8mb4_0900_ai_ci / utf8mb4_0900_as_cs 等在 5.7 上不存在，
     * 直接复制源表 DDL 会抛 "Unknown collation"。这里将 8.0 专有的 utf8mb4_0900_* 排序规则
     * （表级 COLLATE= 与列级 COLLATE ，含索引/字符集声明）统一降级为两版本通用的 utf8mb4_general_ci，
     * 保证同一 DDL 可在高低版本目标库上均能建表。
     */
    private String sanitizeDdlForPortability(String ddl) {
        if (ddl == null) {
            return null;
        }
        // utf8mb4_0900_ai_ci / utf8mb4_0900_as_cs / utf8mb4_0900_bin ... -> utf8mb4_general_ci
        return ddl.replaceAll("(?i)utf8mb4_0900_\\w+", "utf8mb4_general_ci");
    }

    /** 确保目标库存在该表（不存在则按源库 DDL 创建），返回是否成功 */
    public boolean ensureTargetTable(DatabaseConfig sourceDB, DatabaseConfig targetDB, String tableName) {
        if (!tableExists(targetDB, tableName)) {
            String ddl = getCreateTableSql(sourceDB, tableName);
            if (ddl == null) {
                return false;
            }
            Connection conn = null;
            try {
                conn = connMgr.getConnection(targetDB);
                try (Statement st = conn.createStatement()) {
                    st.execute(ddl);
                    log.info("Created target table {} at {}", tableName, targetDB.getHost());
                }
                return true;
            } catch (SQLException e) {
                log.error("Failed to create target table {} at {}", tableName, targetDB.getHost(), e);
                return false;
            } finally {
                closeQuietly(conn, targetDB);
            }
        }
        return true;
    }

    public boolean tableExists(DatabaseConfig cfg, String tableName) {
        Connection conn = null;
        try {
            conn = connMgr.getConnection(cfg);
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SHOW TABLES LIKE '" + tableName + "'")) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        } finally {
            closeQuietly(conn, cfg);
        }
    }

    public void dropTargetTable(DatabaseConfig targetDB, String tableName) {
        Connection conn = null;
        try {
            conn = connMgr.getConnection(targetDB);
            try (Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS `" + tableName + "`");
            }
        } catch (SQLException e) {
            log.warn("Failed to drop target table {}", tableName, e);
        } finally {
            closeQuietly(conn, targetDB);
        }
    }

    public List<String> listTables(DatabaseConfig cfg, String dbName) {
        List<String> tables = new ArrayList<>();
        Connection conn = null;
        try {
            conn = connMgr.getConnection(cfg);
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT TABLE_NAME FROM information_schema.TABLES "
                         + "WHERE TABLE_SCHEMA='" + dbName + "' AND TABLE_TYPE='BASE TABLE'")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to list tables for {}", dbName, e);
        } finally {
            closeQuietly(conn, cfg);
        }
        return tables;
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
}
