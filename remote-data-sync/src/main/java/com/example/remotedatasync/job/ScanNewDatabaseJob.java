package com.example.remotedatasync.job;

import com.example.remotedatasync.config.DRPlatformProperties;
import com.example.remotedatasync.service.DatabaseMetadataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 扫描新增数据库任务：定时发现生产中心新出现的用户库，便于动态提交同步作业。
 *
 * @author 50707
 */
@Slf4j
@Component
public class ScanNewDatabaseJob {

    private final DRPlatformProperties properties;
    private final DatabaseMetadataService metadataService;

    public ScanNewDatabaseJob(DRPlatformProperties properties, DatabaseMetadataService metadataService) {
        this.properties = properties;
        this.metadataService = metadataService;
    }

    @Scheduled(fixedDelay = 60000)
    public void scan() {
        if (properties.getMappings() == null) {
            return;
        }
        for (com.example.remotedatasync.bean.DatabaseMapping mapping : properties.getMappings()) {
            try {
                List<String> dbs = metadataService.getAllUserDatabases(mapping.toSourceHostConfig(), mapping.getIgnoreDatabases());
                log.debug("ScanNewDatabaseJob: source={}, databases={}", mapping.getSourceIP(), dbs);
            } catch (Exception e) {
                log.warn("ScanNewDatabaseJob failed for {}", mapping.getSourceIP(), e);
            }
        }
    }
}
