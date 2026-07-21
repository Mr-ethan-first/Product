package com.example.flinkcdcsync.manager;

import com.example.flinkcdcsync.common.GeoDRSyncErrorCodeEnum;
import com.example.flinkcdcsync.common.IpUtils;
import com.example.flinkcdcsync.common.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * 会话鉴权拦截器：保护 /sync/** 与 /auth/me，未登录返回 401 JSON。
 * <p>
 * 鉴权通过后，将当前用户信息（uid、username）和客户端 IP 存入 {@link UserContext}，
 * 供 AOP 操作日志切面和业务逻辑获取当前操作者信息。
 * 请求结束时在 afterCompletion 中清理 ThreadLocal，防止内存泄漏。
 * </p>
 *
 * @author 50707
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute("uid") != null) {
            // 鉴权通过：填充 UserContext（userId + username + clientIp）
            Long uid = (Long) session.getAttribute("uid");
            String username = (String) session.getAttribute("username");
            String clientIp = IpUtils.getClientIp(request);
            UserContext.set(uid, username, clientIp);
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        String body = "{\"code\":\"" + GeoDRSyncErrorCodeEnum.AUTH_REQUIRED.getCode()
                + "\",\"message\":\"未登录或登录已过期\",\"success\":false}";
        response.getWriter().write(body);
        return false;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 清理 ThreadLocal，防止内存泄漏
        UserContext.clear();
    }
}
