package com.example.remotedatasync.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作日志注解：标记需要记录审计日志的 Controller 方法。
 * <p>
 * 被 {@link com.example.remotedatasync.common.OperationLogAspect} 切面环绕，
 * 自动记录操作者、IP、请求参数、执行结果与耗时到 operation_log 表。
 * </p>
 *
 * @author 50707
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperationLogAnnotation {

    /** 操作类型（如 LOGIN, MAPPING_ADD, MAPPING_REMOVE 等） */
    String type();

    /** 操作描述（人类可读） */
    String desc() default "";

    /** 是否记录请求参数（默认 true） */
    boolean logParams() default true;

    /** 是否记录目标资源（如 instanceKey，从 SpEL 表达式解析，留空则不解析） */
    String targetResource() default "";
}
