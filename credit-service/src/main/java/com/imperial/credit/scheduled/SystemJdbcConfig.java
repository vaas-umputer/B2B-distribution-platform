package com.imperial.credit.scheduled;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * A second DataSource, connected as svc_system (BYPASSRLS - see
 * infra/postgres-init), used ONLY by the two scheduled jobs in this
 * package. Everything else in this service - every controller, every
 * @TenantScoped service method - keeps using the normal, RLS-enforced
 * app_user connection.
 *
 * <p>Why a whole second connection pool instead of just using the same one
 * with elevated privileges: these two jobs are the ONLY code in the entire
 * service that legitimately needs to see every tenant's rows at once (a
 * near-limit scan, a monthly report). Keeping that privilege on a
 * separate, narrowly-named bean means nobody can accidentally reach for it
 * from a request-handling path and bypass isolation by mistake.
 *
 * <p>Defining the PRIMARY DataSource explicitly here (bound to the same
 * spring.datasource.* properties Spring Boot would otherwise auto-configure)
 * is deliberate, not redundant: the moment a second DataSource bean exists
 * in the context, Spring Boot's own DataSourceAutoConfiguration backs off
 * entirely (it only ever auto-configures one), and anything that asks for
 * "the DataSource" with no qualifier becomes ambiguous. Explicitly
 * replicating what auto-configuration would have built, marked @Primary,
 * is the standard fix - everything JPA/Flyway/etc. already depends on
 * keeps working exactly as before, and the system one is reachable only by
 * the beans that explicitly ask for it by name.
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
    public JdbcTemplate systemJdbcTemplate() {
        // Invoke the named bean explicitly: injecting an unqualified
        // DataSource here silently chose the primary app_user pool and made
        // cross-tenant admin reads empty under RLS.
        return new JdbcTemplate(systemDataSource(systemDataSourceProperties()));
    }
}
