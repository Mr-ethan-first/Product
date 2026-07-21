package com.example.remotedatasync.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.remotedatasync.common.BusinessException;
import com.example.remotedatasync.common.DRPlatformErrorCodeEnum;
import com.example.remotedatasync.common.IpUtils;
import com.example.remotedatasync.common.PasswordUtil;
import com.example.remotedatasync.common.Result;
import com.example.remotedatasync.common.UserContext;
import com.example.remotedatasync.common.annotation.OperationLogAnnotation;
import com.example.remotedatasync.mapper.SysUserMapper;
import com.example.remotedatasync.po.SysUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 后台管理鉴权：注册 / 登录 / 登出 / 当前用户。
 * 基于服务器端 HttpSession（同源 Cookie 自动携带），无需前端存储令牌。
 * <p>
 * 登录/登出/注册操作通过 {@link OperationLogAnnotation} 注解自动记录审计日志，
 * 包含操作者用户名、客户端 IP、操作结果。
 * </p>
 *
 * @author 50707
 */
@Slf4j
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final SysUserMapper sysUserMapper;

    public AuthController(SysUserMapper sysUserMapper) {
        this.sysUserMapper = sysUserMapper;
    }

    /** 注册新用户 */
    @OperationLogAnnotation(type = "REGISTER", desc = "用户注册")
    @PostMapping("/register")
    public Result<Void> register(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String username = body == null ? null : body.get("username");
        String password = body == null ? null : body.get("password");
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            throw new BusinessException(DRPlatformErrorCodeEnum.AUTH_PARAM.getCode(), "用户名与密码均不能为空");
        }
        if (username.length() < 3 || username.length() > 32) {
            throw new BusinessException(DRPlatformErrorCodeEnum.AUTH_PARAM.getCode(), "用户名长度需在 3-32 之间");
        }
        if (password.length() < 6) {
            throw new BusinessException(DRPlatformErrorCodeEnum.AUTH_PARAM.getCode(), "密码长度至少 6 位");
        }
        if (sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username)) > 0) {
            throw new BusinessException(DRPlatformErrorCodeEnum.AUTH_DUPLICATE.getCode(), "该用户名已被注册");
        }
        SysUser u = new SysUser();
        u.setUsername(username);
        u.setSalt(PasswordUtil.randomSalt());
        u.setPasswordHash(PasswordUtil.encode(password));
        sysUserMapper.insert(u);
        log.info("Registered new admin user: {} from IP: {}", username, IpUtils.getClientIp(request));
        return Result.success();
    }

    /** 登录：校验成功后写入会话 */
    @OperationLogAnnotation(type = "LOGIN", desc = "用户登录")
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String username = body == null ? null : body.get("username");
        String password = body == null ? null : body.get("password");
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            throw new BusinessException(DRPlatformErrorCodeEnum.AUTH_PARAM.getCode(), "用户名与密码均不能为空");
        }
        SysUser u = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (u == null || !PasswordUtil.matches(password, u.getPasswordHash())) {
            throw new BusinessException(DRPlatformErrorCodeEnum.AUTH_FAIL.getCode(), "用户名或密码错误");
        }
        HttpSession session = request.getSession(true);
        session.setAttribute("uid", u.getId());
        session.setAttribute("username", u.getUsername());
        session.setMaxInactiveInterval(60 * 60 * 8); // 8 小时
        // 登录成功后设置 UserContext（AuthInterceptor 不拦截 /auth/**）
        UserContext.set(u.getId(), u.getUsername(), IpUtils.getClientIp(request));
        Map<String, Object> data = new HashMap<>();
        data.put("username", u.getUsername());
        log.info("User {} logged in from IP: {}", username, IpUtils.getClientIp(request));
        return Result.success(data);
    }

    /** 登出 */
    @OperationLogAnnotation(type = "LOGOUT", desc = "用户登出")
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            // 在 invalidate 前设置 UserContext，供 AOP 操作日志记录操作者信息
            Object uid = session.getAttribute("uid");
            Object username = session.getAttribute("username");
            if (uid instanceof Long && username instanceof String) {
                UserContext.set((Long) uid, (String) username, IpUtils.getClientIp(request));
            }
            session.invalidate();
        }
        return Result.success();
    }

    /**
     * 当前登录用户探测接口。
     * <p>设计要点：该接口<b>永远返回 200</b>，用响应体字段表达登录态，避免浏览器首屏探测时打印 401 控制台噪声。
     * 未登录 -> {loggedIn:false}；已登录 -> {loggedIn:true, data:{username}}。
     * 真正的业务接口（/sync/**）未登录时仍由 {@link com.example.remotedatasync.manager.AuthInterceptor} 返回 401。</p>
     */
    @GetMapping("/me")
    public Result<Map<String, Object>> me(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Map<String, Object> data = new HashMap<>();
        if (session == null || session.getAttribute("uid") == null) {
            data.put("loggedIn", false);
            return Result.success(data);
        }
        data.put("loggedIn", true);
        data.put("username", session.getAttribute("username"));
        return Result.success(data);
    }
}
