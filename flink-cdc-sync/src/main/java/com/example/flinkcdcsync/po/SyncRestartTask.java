package com.example.flinkcdcsync.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 同步重启任务表 sync_restart_task
 *
 * @author 50707
 */
@Data
@NoArgsConstructor
@TableName("sync_restart_task")
public class SyncRestartTask implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 源库主机 */
    private String sourceHost;

    /** 源库名称 */
    private String sourceDb;

    /** 目标库主机 */
    private String targetHost;

    /** 目标库名称 */
    private String targetDb;

    /** 状态 0-待处理 1-处理中 2-成功 3-失败 */
    private Integer status;

    /** 失败原因 */
    private String errorMsg;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
