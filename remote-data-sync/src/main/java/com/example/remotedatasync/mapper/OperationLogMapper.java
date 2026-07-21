package com.example.remotedatasync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.remotedatasync.po.OperationLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 操作日志 Mapper。
 *
 * @author 50707
 */
@Mapper
public interface OperationLogMapper extends BaseMapper<OperationLog> {
}
