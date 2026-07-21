package com.example.flinkcdcsync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.flinkcdcsync.po.SyncRestartTask;
import org.apache.ibatis.annotations.Update;

/**
 * 同步重启任务表 Mapper。
 *
 * @author 50707
 */
public interface SyncRestartTaskMapper extends BaseMapper<SyncRestartTask> {

    @Update("TRUNCATE TABLE sync_restart_task")
    void truncateTable();
}
