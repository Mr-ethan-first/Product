package com.example.remotedatasync.common;

import com.example.remotedatasync.common.annotation.OperationLogAnnotation;
import com.example.remotedatasync.mapper.OperationLogMapper;
import com.example.remotedatasync.po.OperationLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpSession;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 操作日志 AOP 切面：拦截标注了 {@link OperationLogAnnotation} 的方法，
 * 自动记录操作审计日志到 operation_log 表。
 * <p>
 * 记录内容：操作者（user_id + username）、客户端 IP、请求 URL/方法/参数、
 * 执行结果（成功/失败）、错误信息、执行耗时。
 * </p>
 *
 * @author 50707
 */
@Slf4j
@Aspect
@Component
public class OperationLogAspect {

    private final OperationLogMapper operationLogMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 匹配 JSON 中所有含 password/passwd 的字段（不区分大小写），用于脱敏 */
    private static final Pattern PASSWORD_FIELD_PATTERN =
            Pattern.compile("(\"[^\"]*(?:password|passwd)[^\"]*\"\\s*:\\s*)\"[^\"]*\"", Pattern.CASE_INSENSITIVE);

    public OperationLogAspect(OperationLogMapper operationLogMapper) {
        this.operationLogMapper = operationLogMapper;
    }

    @Around("@annotation(operationLogAnnotation)")
    public Object around(ProceedingJoinPoint joinPoint, OperationLogAnnotation operationLogAnnotation) throws Throwable {
        long startTime = System.currentTimeMillis();
        OperationLog opLog = new OperationLog();
        opLog.setOperationType(operationLogAnnotation.type());
        opLog.setOperationDesc(operationLogAnnotation.desc());
        opLog.setCreateTime(LocalDateTime.now());

        // 填充请求信息
        HttpServletRequest request = null;
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            request = attrs.getRequest();
            opLog.setRequestUrl(request.getRequestURI());
            opLog.setRequestMethod(request.getMethod());
        }

        // 填充请求参数
        if (operationLogAnnotation.logParams()) {
            opLog.setRequestParams(truncate(buildParams(joinPoint), 2000));
        }

        Object result;
        try {
            result = joinPoint.proceed();
            opLog.setResultStatus("SUCCESS");
            return result;
        } catch (Throwable e) {
            opLog.setResultStatus("FAILURE");
            opLog.setErrorMsg(truncate(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), 1000));
            throw e;
        } finally {
            opLog.setDurationMs(System.currentTimeMillis() - startTime);
            // 在 proceed 之后读取 UserContext：
            // - /sync/** 接口：AuthInterceptor.preHandle() 已在方法前设置 UserContext
            // - /auth/** 接口：login/register 方法内部才设置 UserContext
            // 因此在 finally 中读取可覆盖两种场景
            if (UserContext.getUserId() != null) {
                opLog.setUserId(UserContext.getUserId());
            }
            if (UserContext.getUsername() != null) {
                opLog.setUsername(UserContext.getUsername());
            }
            if (UserContext.getClientIp() != null) {
                opLog.setClientIp(UserContext.getClientIp());
            } else if (request != null) {
                // UserContext 未设置 IP 时（如 register/logout），直接从 request 获取
                opLog.setClientIp(IpUtils.getClientIp(request));
            }
            // 写入操作日志（不影响主流程）
            try {
                operationLogMapper.insert(opLog);
            } catch (Exception e) {
                log.warn("Failed to persist operation log: {}", e.getMessage());
            }
        }
    }

    /** 构建参数摘要（密码字段统一脱敏） */
    private String buildParams(ProceedingJoinPoint joinPoint) {
        try {
            MethodSignature sig = (MethodSignature) joinPoint.getSignature();
            Method method = sig.getMethod();
            Parameter[] params = method.getParameters();
            Object[] args = joinPoint.getArgs();
            Map<String, Object> paramMap = new HashMap<>();
            for (int i = 0; i < params.length && i < args.length; i++) {
                String name = params[i].getName();
                Object val = args[i];
                // 跳过 HttpServletRequest/Response/Session 等非业务参数
                if (val instanceof HttpServletRequest || val instanceof HttpSession
                        || val instanceof MultipartFile || val instanceof byte[]) {
                    continue;
                }
                paramMap.put(name, val);
            }
            // 序列化为 JSON 后统一脱敏密码字段（覆盖 Map、DTO、嵌套对象）
            String json = objectMapper.writeValueAsString(paramMap);
            return PASSWORD_FIELD_PATTERN.matcher(json).replaceAll("$1\"***\"");
        } catch (Exception e) {
            return Arrays.toString(joinPoint.getArgs());
        }
    }

    /** 截断字符串到指定长度 */
    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
