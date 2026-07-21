package com.example.flinkcdcsync.config;

import com.example.flinkcdcsync.manager.AuthInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置：注册鉴权拦截器，保护业务 API，放行登录/注册等公开接口。
 * <p>
 * 通过 {@code app.auth.enabled}（默认 true）控制是否启用会话鉴权；
 * 自动化集成测试（test profile）将其置为 false，聚焦同步一致性验证。
 * </p>
 *
 * @author 50707
 */
@Slf4j
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    @Value("${app.auth.enabled:true}")
    private boolean authEnabled;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (!authEnabled) {
            log.warn("会话鉴权拦截器已禁用（app.auth.enabled=false），仅应用于测试环境");
            return;
        }
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/sync/**", "/operation-log/**")
                .excludePathPatterns(
                        "/auth/login",
                        "/auth/register",
                        "/auth/me",
                        "/static/**",
                        "/error",
                        "/favicon.ico");
    }
}
