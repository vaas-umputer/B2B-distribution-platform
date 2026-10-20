package com.imperial.invoice.scheduled;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Same pattern as credit-service's SystemJdbcConfig - see its Javadoc for
 * the full reasoning. Here the one genuinely cross-tenant job is the daily
 * overdue-invoice sweep: finding every ISSUED invoice past its due date,
 * across every tenant, in one query, is exactly what the normal
 * RLS-enforced app_user connection cannot do (by design).
 */
@Configuration
public class SystemJdbcConfig {

    @Primary
    @Bean
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    @Primary
    @Bean
    public DataSource dataSource(DataSourceProperties dataSourceProperties) {
        return dataSourceProperties.initializeDataSourceBuilder().build();
    }

    @Bean
    @ConfigurationProperties("system.datasource")
    public DataSourceProperties systemDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    public DataSource systemDataSource(DataSourceProperties systemDataSourceProperties) {
        return systemDataSourceProperties.initializeDataSourceBuilder().build();
    }

    @Bean
    public JdbcTemplate systemJdbcTemplate(DataSource systemDataSource) {
        return new JdbcTemplate(systemDataSource);
    }
}
