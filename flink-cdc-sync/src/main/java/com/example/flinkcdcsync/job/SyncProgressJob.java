package com.example.flinkcdcsync.job;

import com.example.flinkcdcsync.service.SyncProgressService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 同步进度生成任务：将内存进度缓存持久化到 sync_progress 表。
 *
 * @author 50707
 */
@Slf4j
@Component
public class SyncProgressJob {

    private final SyncProgressService syncProgressService;

    public SyncProgressJob(SyncProgressService syncProgressService) {
        this.syncProgressService = syncProgressService;
    }

    @Scheduled(fixedDelay = 10000)
    public void persistProgress() {
        try {
            syncProgressService.persistFromMemory();
        } catch (Exception e) {
            log.warn("SyncProgressJob persist failed", e);
        }
    }
}
