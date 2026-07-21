package com.example.flinkcdcsync.job;

import com.example.flinkcdcsync.bean.DatabaseConfig;
import com.example.flinkcdcsync.config.GeoDRSyncProperties;
import com.example.flinkcdcsync.manager.DynamicShardedConnectionManager;
import com.example.flinkcdcsync.mapper.SourceLatestBinlogInfoMapper;
import com.example.flinkcdcsync.po.SourceLatestBinlogInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 源库最新 Binlog 信息备份任务：定时采集各生产库最新 binlog 文件/位置。
 *
 * @author 50707
 */
@Slf4j
@Component
public class SourceBinlogJob {

    private final GeoDRSyncProperties properties;
    private final DynamicShardedConnectionManager connMgr;
    private final SourceLatestBinlogInfoMapper binlogMapper;

    public SourceBinlogJob(GeoDRSyncProperties properties, DynamicShardedConnectionManager connMgr,
                           SourceLatestBinlogInfoMapper binlogMapper) {
        this.properties = properties;
        this.connMgr = connMgr;
        this.binlogMapper = binlogMapper;
    }

    @Scheduled(fixedDelay = 30000)
    public void backupBinlog() {
        if (properties.getMappings() == null) {
            return;
        }
        for (com.example.flinkcdcsync.bean.DatabaseMapping mapping : properties.getMappings()) {
            DatabaseConfig source = mapping.toSourceHostConfig();
            try {
                backupOne(source);
            } catch (Exception e) {
                log.warn("SourceBinlogJob failed for {}", source.getHost(), e);
            }
        }
    }

    private void backupOne(DatabaseConfig cfg) {
        Connection conn = null;
        try {
            conn = connMgr.getConnection(cfg);
            String file = null;
            long pos = 0;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SHOW MASTER STATUS")) {
                if (rs.next()) {
                    file = rs.getString("File");
                    pos = rs.getLong("Position");
                }
            }
            if (file == null) {
                log.debug("SHOW MASTER STATUS returned no row for {} (not a master / may be a replica)", cfg.getHost());
                return;
            }
            SourceLatestBinlogInfo info = new SourceLatestBinlogInfo();
            info.setSourceIp(cfg.getHost());
            info.setSourceDbName("*");
            info.setSourceBinlogFile(file);
            info.setSourceBinlogPos(pos);
            info.setSourceBinlogTime(LocalDateTime.now());
            info.setCreateTime(LocalDateTime.now());
            info.setUpdateTime(LocalDateTime.now());
            // 幂等写入：基于唯一键 (source_ip, source_db_name) 插入或更新，避免重复插入冲突
            binlogMapper.upsert(info);
        } catch (Exception e) {
            log.warn("SourceBinlogJob failed to backup binlog for {}: {}", cfg.getHost(), e.getMessage());
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (Exception ignore) {
                }
                connMgr.releaseConnection();
            }
        }
    }
}
