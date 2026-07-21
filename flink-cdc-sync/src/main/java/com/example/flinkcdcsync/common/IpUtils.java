package com.example.flinkcdcsync.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端 IP 工具类：从 HttpServletRequest 中获取真实客户端 IP。
 * <p>
 * 优先级：X-Forwarded-For → X-Real-IP → Proxy-Client-IP → WL-Proxy-Client-IP → HTTP_CLIENT_IP → HTTP_X_FORWARDED_FOR → getRemoteAddr
 * </p>
 *
 * @author 50707
 */
public final class IpUtils {

    private IpUtils() {}

    private static final String[] IP_HEADERS = {
        "X-Forwarded-For",
        "X-Real-IP",
        "Proxy-Client-IP",
        "WL-Proxy-Client-IP",
        "HTTP_CLIENT_IP",
        "HTTP_X_FORWARDED_FOR"
    };

    /**
     * 获取客户端真实 IP。
     * <p>
     * X-Forwarded-For 可能包含多个 IP（代理链），取第一个非 unknown 的。
     * 本地开发环境返回 127.0.0.1 或 0:0:0:0:0:0:0:1。
     * </p>
     */
    public static String getClientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        for (String header : IP_HEADERS) {
            String ip = request.getHeader(header);
            if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                // X-Forwarded-For 格式: client, proxy1, proxy2
                int comma = ip.indexOf(',');
                if (comma > 0) {
                    ip = ip.substring(0, comma).trim();
                }
                return ip;
            }
        }
        String ip = request.getRemoteAddr();
        // IPv6 本地回环
        if ("0:0:0:0:0:0:0:1".equals(ip)) {
            return "127.0.0.1";
        }
        return ip != null ? ip : "unknown";
    }
}
