package com.smartcampus.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import javax.sql.DataSource;

@Configuration
public class DataSourceConfig {

    private final String jdbcUrl;
    private final String username;
    private final String password;

    // 构造函数注入，确保值可用
    public DataSourceConfig(
            @Value("${spring.datasource.url:jdbc:postgresql://localhost:5432/smart_campus}") String jdbcUrl,
            @Value("${spring.datasource.username:smartcampus_app}") String username,
            @Value("${spring.datasource.password:SmartCampus2024}") String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        System.out.println("DataSourceConfig初始化完成");
    }

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    @Profile("prod")
    public DataSource prodDataSource() {
        System.out.println("创建prodDataSource...");

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password != null ? password : "SmartCampus2024"); // 双重保险

        System.out.println("配置: URL=" + jdbcUrl + ", User=" + username + ", Password=" + (password != null ? "***" : "NULL"));

        // 简化配置，先确保能连接
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30000);
        config.setConnectionInitSql("SELECT 1");
        config.setPoolName("SmartCampusProdPool");

        return new HikariDataSource(config);
    }

    @Bean
    @Profile("dev")
    public DataSource devDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://localhost:5432/smart_campus");
        config.setUsername("postgres");
        config.setPassword("123456");
        config.setMaximumPoolSize(5);
        return new HikariDataSource(config);
    }
}