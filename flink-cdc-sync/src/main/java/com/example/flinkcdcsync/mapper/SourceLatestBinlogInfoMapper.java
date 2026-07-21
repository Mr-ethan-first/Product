package com.example.flinkcdcsync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.flinkcdcsync.po.SourceLatestBinlogInfo;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;

/**
 * 源库最新 Binlog 信息表 Mapper。
 *
 * @author 50707
 */
public interface SourceLatestBinlogInfoMapper extends BaseMapper<SourceLatestBinlogInfo> {

    @Update("TRUNCATE TABLE source_latest_binlog_info")
    void truncateTable();

    /**
     * 幂等写入：基于唯一键 (SOURCE_IP, SOURCE_DB_NAME) 插入或更新最新 binlog 位点，
     * 避免定时任务每次轮询都因唯一键冲突抛 Duplicate entry 异常。
     */
    @Insert("INSERT INTO source_latest_binlog_info (source_ip, source_db_name, source_binlog_file, source_binlog_pos, source_binlog_time, create_time, update_time) "
            + "VALUES (#{sourceIp}, #{sourceDbName}, #{sourceBinlogFile}, #{sourceBinlogPos}, #{sourceBinlogTime}, #{createTime}, #{updateTime}) "
            + "ON DUPLICATE KEY UPDATE source_binlog_file = VALUES(source_binlog_file), "
            + "source_binlog_pos = VALUES(source_binlog_pos), source_binlog_time = VALUES(source_binlog_time), update_time = VALUES(update_time)")
    void upsert(SourceLatestBinlogInfo info);
}
