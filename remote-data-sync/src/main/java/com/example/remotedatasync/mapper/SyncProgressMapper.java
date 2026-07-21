package com.example.remotedatasync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.remotedatasync.po.SyncProgress;
import org.apache.ibatis.annotations.Update;

/**
 * 同步进度表 Mapper。
 *
 * @author 50707
 */
public interface SyncProgressMapper extends BaseMapper<SyncProgress> {

    @Update("TRUNCATE TABLE sync_progress")
    void truncateTable();
}
