package com.example.remotedatasync.job;

import com.example.remotedatasync.manager.DatabaseSyncManager;
import com.example.remotedatasync.mapper.DbhaSwitchStatusMapper;
import com.example.remotedatasync.po.DbhaSwitchStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 主备切换状态扫描任务：监听 dbha_switch_status 表变化，触发同步作业平滑重建。
 * <p>
 * 说明：自动重建默认关闭（switchAutoResync=false），避免对自动化测试造成干扰；
 * 生产环境可开启，或保持由人工通过 /sync/resyncDatabases 触发。
 * </p>
 *
 * @author 50707
 */
@Slf4j
@Component
public class DBHASwitchStatusJob {

    private final DbhaSwitchStatusMapper switchStatusMapper;
    private final DatabaseSyncManager databaseSyncManager;
    private final boolean switchAutoResync = false;

    public DBHASwitchStatusJob(DbhaSwitchStatusMapper switchStatusMapper, DatabaseSyncManager databaseSyncManager) {
        this.switchStatusMapper = switchStatusMapper;
        this.databaseSyncManager = databaseSyncManager;
    }

    @Scheduled(fixedDelay = 15000)
    public void scanSwitchStatus() {
        try {
            List<DbhaSwitchStatus> list = switchStatusMapper.selectList(null);
            if (list == null || list.isEmpty()) {
                return;
            }
            for (DbhaSwitchStatus s : list) {
                log.info("Detected HA switch for db={}, mainIp={} -> standbyIp={}",
                        s.getSourceDbName(), s.getMainIp(), s.getStandbyIp());
                if (switchAutoResync && s.getSourceDbName() != null) {
                    com.example.remotedatasync.dto.SyncProgressQuery q = new com.example.remotedatasync.dto.SyncProgressQuery();
                    q.setIp(s.getMainIp());
                    q.setDbName(s.getSourceDbName());
                    databaseSyncManager.resyncDatabases(List.of(q));
                }
            }
        } catch (Exception e) {
            log.warn("DBHASwitchStatusJob failed", e);
        }
    }
}
