package com.example.remotedatasync.manager;

import com.example.remotedatasync.mapper.SyncRestartTaskMapper;
import com.example.remotedatasync.po.SyncRestartTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 同步重启任务管理：将 CREATE/DROP 等需要全量刷新的操作登记到 sync_restart_task 表。
 *
 * @author 50707
 */
@Slf4j
@Component
public class RestartTaskManager {

    private final SyncRestartTaskMapper restartTaskMapper;

    public RestartTaskManager(SyncRestartTaskMapper restartTaskMapper) {
        this.restartTaskMapper = restartTaskMapper;
    }

    public void recordTask(String sourceHost, String sourceDb, String targetHost, String targetDb, String action) {
        try {
            SyncRestartTask task = new SyncRestartTask();
            task.setSourceHost(sourceHost);
            task.setSourceDb(sourceDb);
            task.setTargetHost(targetHost);
            task.setTargetDb(targetDb);
            task.setStatus(2); // 成功
            task.setErrorMsg(action);
            task.setCreateTime(LocalDateTime.now());
            task.setUpdateTime(LocalDateTime.now());
            restartTaskMapper.insert(task);
        } catch (Exception e) {
            log.warn("Failed to record restart task for {}.{}", sourceDb, action, e);
        }
    }
}
