package com.smartcampus.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class DatabaseHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseHealthIndicator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Health health() {
        try {
            // 执行简单查询测试连接
            Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);

            // 检查关键表是否存在
            Integer userCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'users'",
                    Integer.class
            );

            // 获取数据库信息
            String dbVersion = jdbcTemplate.queryForObject(
                    "SELECT version()", String.class
            );

            return Health.up()
                    .withDetail("database", "connected")
                    .withDetail("version", dbVersion)
                    .withDetail("users_table_exists", userCount > 0 ? "yes" : "no")
                    .build();

        } catch (Exception e) {
            log.error("数据库健康检查失败", e);
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .withDetail("timestamp", System.currentTimeMillis())
                    .build();
        }
    }
}