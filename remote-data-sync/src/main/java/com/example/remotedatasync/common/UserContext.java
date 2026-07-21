package com.example.remotedatasync.common;

/**
 * 用户上下文（ThreadLocal）：存储当前请求的登录用户信息与客户端 IP。
 * <p>
 * 由 {@link com.example.remotedatasync.manager.AuthInterceptor} 在鉴权通过后填充，
 * 供 AOP 操作日志切面、Controller、Service 获取当前操作者信息。
 * </p>
 *
 * @author 50707
 */
public class UserContext {

    private static final ThreadLocal<UserContext> HOLDER = new ThreadLocal<>();

    private Long userId;
    private String username;
    private String clientIp;

    private UserContext() {}

    /** 设置当前线程的用户上下文 */
    public static void set(Long userId, String username, String clientIp) {
        UserContext ctx = new UserContext();
        ctx.userId = userId;
        ctx.username = username;
        ctx.clientIp = clientIp;
        HOLDER.set(ctx);
    }

    /** 获取当前线程的用户上下文 */
    public static UserContext get() {
        return HOLDER.get();
    }

    /** 获取当前用户 ID（未登录返回 null） */
    public static Long getUserId() {
        UserContext ctx = HOLDER.get();
        return ctx == null ? null : ctx.userId;
    }

    /** 获取当前用户名（未登录返回 null） */
    public static String getUsername() {
        UserContext ctx = HOLDER.get();
        return ctx == null ? null : ctx.username;
    }

    /** 获取当前客户端 IP（未设置返回 null） */
    public static String getClientIp() {
        UserContext ctx = HOLDER.get();
        return ctx == null ? null : ctx.clientIp;
    }

    /** 清除当前线程的用户上下文（防止内存泄漏） */
    public static void clear() {
        HOLDER.remove();
    }

    public Long getUserIdValue() { return userId; }
    public String getUsernameValue() { return username; }
    public String getClientIpValue() { return clientIp; }
}
