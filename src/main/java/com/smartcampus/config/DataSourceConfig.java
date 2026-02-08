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

    @Value("${spring.datasource.url:jdbc:postgresql://localhost:5432/smart_campus}")
    private String jdbcUrl;

    @Value("${spring.datasource.username:smartcampus_app}")
    private String username;

    @Value("${spring.datasource.password:SmartCampus2024}")
    private String password;

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    @Profile("prod")
    public DataSource prodDataSource() {
        HikariConfig config = new HikariConfig();

        // 基础配置 - 从配置文件读取
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password); // 修复：从配置文件读取，不是环境变量

        // 连接池优化
        config.setMaximumPoolSize(20);
        config.setMinimumIdle(10);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        config.setLeakDetectionThreshold(60000);
        config.setConnectionInitSql("SELECT 1"); // 修复：connection-init-sql
        config.setPoolName("SmartCampusProdPool");

        // PostgreSQL特定优化
        config.addDataSourceProperty("prepareThreshold", "5");
        config.addDataSourceProperty("preparedStatementCacheQueries", "256");
        config.addDataSourceProperty("preparedStatementCacheSizeMiB", "5");
        config.addDataSourceProperty("assumeMinServerVersion", "12");
        config.addDataSourceProperty("ApplicationName", "smart_campus_backend");

        // 连接验证
        config.setValidationTimeout(5000);
        config.setInitializationFailTimeout(60000);

        return new HikariDataSource(config);
    }

    @Bean
    @Profile("dev")
    public DataSource devDataSource() {
        // 开发环境配置（简化）
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://localhost:5432/smart_campus");
        config.setUsername("postgres");
        config.setPassword("123456");
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(5);
        return new HikariDataSource(config);
    }
}