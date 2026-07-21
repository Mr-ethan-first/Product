package com.example.flinkcdcsync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.flinkcdcsync.po.DbhaSwitchStatus;
import org.apache.ibatis.annotations.Update;

/**
 * 主备切换状态表 Mapper。
 *
 * @author 50707
 */
public interface DbhaSwitchStatusMapper extends BaseMapper<DbhaSwitchStatus> {

    @Update("TRUNCATE TABLE dbha_switch_status")
    void truncateTable();
}
