package com.imperial.tenant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * ComponentScan is widened to "com.imperial" (beyond this package) so the
 * shared beans in the common module - CorrelationIdFilter,
 * CorrelationIdFeignInterceptor, TenantRlsAspect - are picked up without
 * every service having to redeclare them.
 *
 * <p>EnableTransactionManagement(order = 0) forces the @Transactional proxy
 * to be the OUTERMOST advice around a @TenantScoped method, so TenantRlsAspect
 * (see its own Javadoc) runs after the transaction has started, not before.
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.imperial")
@EnableTransactionManagement(order = 0)
@EnableMethodSecurity
public class TenantCatalogServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TenantCatalogServiceApplication.class, args);
    }
}
