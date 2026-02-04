package com.smartcampus.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 1. 禁用 CSRF（对 API 接口很常见）
                .csrf(AbstractHttpConfigurer::disable)

                // 2. 启用 CORS
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // 3. 配置请求授权
                .authorizeHttpRequests(authorize -> authorize
                                // 允许所有人访问以下路径
                                .requestMatchers(
                                        "/api/login",
                                        "/api/register",
                                        "/api/verify/**",
                                        "/api/test",
                                        "/api/users"
                                ).permitAll()

                                // 允许所有人访问 /api 路径（根据你的需求调整）
                                .requestMatchers("/api/**").permitAll()

                                // 允许访问静态资源
                                .requestMatchers("/**").permitAll()

                        // 如果需要认证其他路径
                        // .anyRequest().authenticated()
                )

                // 4. 禁用默认的表单登录
                .formLogin(AbstractHttpConfigurer::disable)

                // 5. 禁用 HTTP 基本认证
                .httpBasic(AbstractHttpConfigurer::disable)

                // 6. 禁用默认的注销页面
                .logout(AbstractHttpConfigurer::disable);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // 允许的源
        configuration.setAllowedOrigins(Arrays.asList(
                "http://localhost:5173",
                "http://127.0.0.1:5173"
        ));

        // 允许的请求方法
        configuration.setAllowedMethods(Arrays.asList(
                "GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"
        ));

        // 允许的请求头
        configuration.setAllowedHeaders(Arrays.asList(
                "Authorization",
                "Content-Type",
                "X-Requested-With",
                "Accept",
                "Origin",
                "Access-Control-Request-Method",
                "Access-Control-Request-Headers"
        ));

        // 允许暴露的响应头
        configuration.setExposedHeaders(Arrays.asList(
                "Access-Control-Allow-Origin",
                "Access-Control-Allow-Credentials",
                "Authorization"
        ));

        // 允许携带凭证（cookies等）
        configuration.setAllowCredentials(true);

        // 预检请求的缓存时间（秒）
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);

        return source;
    }
}