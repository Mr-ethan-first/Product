package com.example.flinkcdcsync.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 同步进度表 sync_progress
 *
 * @author 50707
 */
@Data
@NoArgsConstructor
@TableName("sync_progress")
public class SyncProgress implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 生产中心数据库IP */
    private String sourceIp;

    /** 生产中心数据库名称 */
    private String sourceDbName;

    /** 灾备中心数据库IP */
    private String targetIp;

    /** 灾备中心数据库名称 */
    private String targetDbName;

    /** 同步状态 (0-失效,1-全量,2-同步中,3-中止) */
    private Integer state;

    /** 同步开始时间 */
    private LocalDateTime syncStartTime;

    /** 生产中心最新binlog文件 */
    private String sourceBinlogFile;

    /** 生产中心binlog时间 */
    private LocalDateTime sourceBinlogTime;

    /** 同步到的binlog文件 */
    private String syncBinlogFile;

    /** 同步到的binlog时间 */
    private LocalDateTime syncBinlogTime;

    /** 时间偏差（秒） */
    private Long deviationTimes;

    /** 偏差状态 (1-正常,2-异常) */
    private Integer deviationStatus;

    /** 同步中止原因 */
    private String suspensionReason;

    /** 建议处理方法 */
    private String processingMethod;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 内存缓存 key：sourceIp|sourceDbName */
    public String getSyncProgressMapKey() {
        return sourceIp + "|" + sourceDbName;
    }
}
