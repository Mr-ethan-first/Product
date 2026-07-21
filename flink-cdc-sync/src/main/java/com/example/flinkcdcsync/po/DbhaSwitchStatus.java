package com.example.flinkcdcsync.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 主备切换状态表 dbha_switch_status
 *
 * @author 50707
 */
@Data
@NoArgsConstructor
@TableName("dbha_switch_status")
public class DbhaSwitchStatus implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 生产中心虚IP */
    private String virtualIp;

    /** 原主IP */
    private String oldMainIp;

    /** 原备IP */
    private String oldStandbyIp;

    /** 新主IP */
    private String mainIp;

    /** 新备IP */
    private String standbyIp;

    /** 最近切换时间 */
    private LocalDateTime switchTime;

    /** 数据库名称 */
    private String sourceDbName;

    /** 生产中心最新binlog文件 */
    private String sourceBinlogFile;

    /** 生产中心binlog偏移量 */
    private Long sourceBinlogPos;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
