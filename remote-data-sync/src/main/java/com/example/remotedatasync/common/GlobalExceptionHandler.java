package com.example.remotedatasync.common;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理，统一返回 {@link Result} 结构。
 *
 * @author 50707
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException ex, HttpServletRequest request) {
        log.warn("BusinessException uri={} code={} msg={}", request.getRequestURI(), ex.getCode(), ex.getMessage());
        return ResponseEntity.status(resolveHttpStatus(ex.getCode()))
                .body(Result.error(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValid(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("Validation failed uri={} msg={}", request.getRequestURI(), msg);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(DRPlatformErrorCodeEnum.PARAM_ERROR.getCode(), msg));
    }

    /** 路径/请求参数类型不匹配（如 /sync/abc 中 abc 无法转 Long） */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String msg = "参数类型不匹配: " + ex.getName() + " 无法接受值 '" + ex.getValue() + "'";
        log.warn("TypeMismatch uri={} msg={}", request.getRequestURI(), msg);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(DRPlatformErrorCodeEnum.PARAM_ERROR.getCode(), msg));
    }

    /** 请求体不可读（空 body / JSON 格式错误） */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleNotReadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.warn("MessageNotReadable uri={} msg={}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(DRPlatformErrorCodeEnum.PARAM_ERROR.getCode(), "请求体缺失或格式错误"));
    }

    /** 缺少必填查询参数 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParam(MissingServletRequestParameterException ex, HttpServletRequest request) {
        String msg = "缺少必填参数: " + ex.getParameterName();
        log.warn("MissingParam uri={} msg={}", request.getRequestURI(), msg);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.error(DRPlatformErrorCodeEnum.PARAM_ERROR.getCode(), msg));
    }

    /** Content-Type 不支持 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        log.warn("MediaTypeNotSupported uri={} msg={}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(Result.error(DRPlatformErrorCodeEnum.PARAM_ERROR.getCode(), "不支持的 Content-Type: " + ex.getContentType()));
    }

    /** HTTP 方法不支持 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        log.warn("MethodNotSupported uri={} msg={}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Result.error(DRPlatformErrorCodeEnum.PARAM_ERROR.getCode(), "不支持的请求方法: " + ex.getMethod()));
    }

    /** 资源不存在（访问未映射的路径）返回 404，而非被 catch-all 误判为 500。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        log.warn("NoResourceFound uri={}", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.error(DRPlatformErrorCodeEnum.PATH_NOT_FOUND.getCode(), "接口不存在: " + request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpected(Exception ex, HttpServletRequest request) {
        // 安全与健壮性：对外只给通用错误信息，不泄露内部异常栈/消息；详细堆栈仅留服务端日志。
        String traceId = TraceContext.getTraceId();
        log.error("Unexpected error uri={} traceId={}", request.getRequestURI(), traceId, ex);
        String detail = (traceId != null) ? "服务器内部错误，请稍后重试（跟踪号：" + traceId + "）" : "服务器内部错误，请稍后重试或联系管理员";
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(DRPlatformErrorCodeEnum.SYNC_ENGINE_ERROR.getCode(), detail));
    }

    private int resolveHttpStatus(String code) {
        DRPlatformErrorCodeEnum e = DRPlatformErrorCodeEnum.getByCode(code);
        return e == null ? HttpStatus.INTERNAL_SERVER_ERROR.value() : e.getHttpStatus();
    }
}
