package com.example.flinkcdcsync.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 源数据库最新Binlog信息表 source_latest_binlog_info
 *
 * @author 50707
 */
@Data
@NoArgsConstructor
@TableName("source_latest_binlog_info")
public class SourceLatestBinlogInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 生产中心IP */
    private String sourceIp;

    /** 数据库名称 */
    private String sourceDbName;

    /** 最新的binlog文件 */
    private String sourceBinlogFile;

    /** binlog的最新时间 */
    private LocalDateTime sourceBinlogTime;

    /** binlog的偏移量 */
    private Long sourceBinlogPos;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
