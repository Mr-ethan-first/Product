package com.example.flinkcdcsync.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 操作审计日志：记录用户对系统的关键操作（登录/登出/增删改配置/同步控制等）。
 * <p>
 * 每条记录包含：操作者信息（user_id + username）、操作类型与描述、
 * 目标资源、客户端 IP、请求 URL 与方法、请求参数摘要、执行结果与耗时。
 * </p>
 *
 * @author 50707
 */
@Data
@TableName("operation_log")
public class OperationLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作者用户 ID */
    private Long userId;

    /** 操作者用户名 */
    private String username;

    /** 操作类型（LOGIN, LOGOUT, MAPPING_ADD, MAPPING_REMOVE, MAPPING_RELOAD, MAPPING_UPDATE, RESYNC, TEST_CONNECTION 等） */
    private String operationType;

    /** 操作描述（人类可读） */
    private String operationDesc;

    /** 目标资源（如 instanceKey、sourceHost->targetHost） */
    private String targetResource;

    /** 客户端 IP */
    private String clientIp;

    /** 请求 URL */
    private String requestUrl;

    /** HTTP 方法（GET/POST/PUT/DELETE） */
    private String requestMethod;

    /** 请求参数摘要（截断到 2000 字符） */
    private String requestParams;

    /** 执行结果（SUCCESS / FAILURE） */
    private String resultStatus;

    /** 错误信息（失败时记录，截断到 1000 字符） */
    private String errorMsg;

    /** 执行耗时（毫秒） */
    private Long durationMs;

    /** 操作时间 */
    private LocalDateTime createTime;
}
