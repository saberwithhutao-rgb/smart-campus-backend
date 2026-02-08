package com.smartcampus.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import javax.sql.DataSource;

@Configuration
public class DataSourceConfig {

    private final String jdbcUrl;
    private final String username;
    private final String password;

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
    @Profile("prod")
    public DataSource prodDataSource() {
        System.out.println("创建prodDataSource...");
        System.out.println("配置: URL=" + jdbcUrl + ", User=" + username);

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password != null ? password : "SmartCampus2024");

        // 简化配置
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30000);
        config.setConnectionInitSql("SELECT 1");
        config.setPoolName("SmartCampusProdPool");

        // 移除可能出问题的复杂配置
        // config.addDataSourceProperty("prepareThreshold", "5");
        // config.addDataSourceProperty("preparedStatementCacheQueries", "256");

        // 重要：不设置这些，避免冲突
        // config.setIdleTimeout(600000);
        // config.setMaxLifetime(1800000);
        // config.setLeakDetectionThreshold(60000);

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