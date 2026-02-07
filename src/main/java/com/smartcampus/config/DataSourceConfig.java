package com.smartcampus.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import javax.sql.DataSource;

@Configuration
public class DataSourceConfig {

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    @Profile("prod")
    public DataSource prodDataSource() {
        HikariConfig config = new HikariConfig();

        // 基础配置
        config.setJdbcUrl("jdbc:postgresql://localhost:5432/smart_campus");
        config.setUsername("smartcampus_app");
        config.setPassword(System.getenv("DB_PASSWORD")); // 从环境变量获取

        // 连接池优化
        config.setMaximumPoolSize(20);
        config.setMinimumIdle(10);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        config.setLeakDetectionThreshold(60000);
        config.setConnectionTestQuery("SELECT 1");
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