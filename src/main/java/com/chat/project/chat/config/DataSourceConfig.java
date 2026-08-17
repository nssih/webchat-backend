package com.chat.project.chat.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    public DataSource dataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username:#{null}}") String username,
            @Value("${spring.datasource.password:#{null}}") String password,
            @Value("${spring.datasource.driver-class-name:#{null}}") String driverClassName,
            @Value("${webchat.datasource.pool-size:5}") int poolSize,
            @Value("${webchat.datasource.init-sql:#{null}}") String initSql) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        if (username != null) config.setUsername(username);
        if (password != null) config.setPassword(password);
        if (driverClassName != null) config.setDriverClassName(driverClassName);
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(Math.min(poolSize, 2));
        config.setConnectionTimeout(30000);
        if (initSql != null && !initSql.isBlank()) config.setConnectionInitSql(initSql);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        config.setKeepaliveTime(120000);
        return new HikariDataSource(config);
    }
}