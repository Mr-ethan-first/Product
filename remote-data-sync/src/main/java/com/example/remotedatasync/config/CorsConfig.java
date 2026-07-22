package com.example.remotedatasync.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;

/**
 * 全局跨域配置：允许前端独立部署（如 Nginx / http-server）调用本服务 REST 接口。
 * <p>
 * 安全约束：<b>绝不使用通配符 {@code *} 与 allowCredentials(true) 的组合</b>
 * （该组合会被浏览器在携带凭证时反射任意 Origin，构成 CSRF 高危）。
 * 跨域来源必须通过 {@code drplatform.cors.allowed-origins} 显式配置（逗号分隔）；
 * 未配置时仅允许同源，跨域请求一律不回显 Access-Control-Allow-Origin，杜绝 CSRF。
 *
 * @author 50707
 */
@Configuration
public class CorsConfig {

    @Value("${drplatform.cors.allowed-origins:}")
    private String allowedOriginsRaw;

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        List<String> origins = new ArrayList<>();
        if (allowedOriginsRaw != null && !allowedOriginsRaw.isBlank()) {
            for (String s : allowedOriginsRaw.split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) {
                    origins.add(t);
                }
            }
        }
        return new WebMvcConfigurer() {
            @Override
            public void addViewControllers(ViewControllerRegistry registry) {
                // 欢迎页：访问根路径直接展示前端看板，避免 GET / 命中静态资源处理器抛出 NoResourceFoundException -> 5008
                registry.addViewController("/").setViewName("forward:/index.html");
            }

            @Override
            public void addCorsMappings(CorsRegistry registry) {
                if (origins.isEmpty()) {
                    // 未配置跨域来源：仅允许同源；空 allowedOrigins 使跨域请求不回显 ACAO，杜绝 CSRF
                    registry.addMapping("/**")
                            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                            .allowedHeaders("*")
                            .maxAge(3600)
                            .allowedOrigins();
                } else {
                    // 显式来源列表：可安全携带凭证（不使用通配符 *）
                    registry.addMapping("/**")
                            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                            .allowedHeaders("*")
                            .maxAge(3600)
                            .allowedOrigins(origins.toArray(new String[0]))
                            .allowCredentials(true);
                }
            }
        };
    }
}
