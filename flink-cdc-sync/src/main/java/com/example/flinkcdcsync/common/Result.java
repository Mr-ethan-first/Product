package com.example.flinkcdcsync.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应结构（强制）。
 * <p>
 * 所有 HTTP 接口返回必须符合：code / message / traceId / data / success
 * </p>
 *
 * @param <T> 业务数据类型
 * @author 50707
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 错误码（10 位数字，格式 [组件][模块][具体]），成功时为 "0" */
    private String code;

    /** 提示信息 */
    private String message;

    /** 链路追踪 ID，由拦截器/过滤器生成并在日志中透传 */
    private String traceId;

    /** 业务数据 */
    private T data;

    /** 是否成功 */
    private boolean success;

    public Result() {
    }

    public Result(String code, String message, T data, boolean success) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.success = success;
        this.traceId = TraceContext.getTraceId();
    }

    public static <T> Result<T> success(T data) {
        return new Result<>("0", "success", data, true);
    }

    public static <T> Result<T> success() {
        return new Result<>("0", "success", null, true);
    }

    public static <T> Result<T> error(String code, String message) {
        return new Result<>(code, message, null, false);
    }

    public static <T> Result<T> error(String code, String message, T data) {
        return new Result<>(code, message, data, false);
    }
}
