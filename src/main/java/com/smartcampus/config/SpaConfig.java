package com.smartcampus.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SpaConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 🟢 按模块添加所有前端路由前缀
        // AI学习模块
        registry.addViewController("/ai/study").setViewName("forward:/index.html");
        registry.addViewController("/ai/study/**").setViewName("forward:/index.html");
        registry.addViewController("/ai/history").setViewName("forward:/index.html");

        // 就业模块
        registry.addViewController("/career/**").setViewName("forward:/index.html");

        // 校园模块
        registry.addViewController("/campus/**").setViewName("forward:/index.html");

        // 个人中心
        registry.addViewController("/profile").setViewName("forward:/index.html");
        registry.addViewController("/profile/**").setViewName("forward:/index.html");

        // 基础页面
        registry.addViewController("/").setViewName("forward:/index.html");
        registry.addViewController("/login").setViewName("forward:/index.html");
        registry.addViewController("/register").setViewName("forward:/index.html");
        registry.addViewController("/dashboard").setViewName("forward:/index.html");
    }
}