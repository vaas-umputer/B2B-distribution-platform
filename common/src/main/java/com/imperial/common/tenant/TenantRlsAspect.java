package com.imperial.common.tenant;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Stamps the current transaction's Postgres session with the caller's tenant
 * id, so every RLS policy on every table that checks
 * current_setting('app.current_tenant') enforces isolation automatically -
 * tenant filtering lives in the database, not in a WHERE clause someone could
 * forget to add.
 *
 * <p>Uses set_config('app.current_tenant', <id>, true) rather than a plain
 * "SET LOCAL ... = '<id>'" string: the third argument (is_local = true) gives
 * the same transaction-scoped reset as SET LOCAL, but set_config is a regular
 * SQL function that takes a bind parameter, so the tenant id never has to be
 * concatenated into SQL text.
 *
 * <p>Ordering is the one subtlety here. Spring's @Transactional advice must
 * run OUTER to this aspect (start the transaction first) so that when this
 * aspect's SET LOCAL-equivalent call executes, it's already inside that
 * transaction and therefore reset automatically on commit/rollback - never
 * surviving into whatever request the pooled connection serves next. The
 * service's @EnableTransactionManagement(order = 0) makes the transaction
 * advice outermost; this aspect's @Order(something greater than 0) makes it
 * run just inside that boundary, immediately before the real method body.
 *
 * <p><b>A real Postgres gotcha this implies:</b> the first time a custom GUC
 * namespace key (like app.current_tenant) is ever referenced on a given
 * physical connection, Postgres creates a placeholder for it that persists
 * for the rest of that connection's life. is_local correctly reverts the
 * VALUE on commit/rollback, but "reverts to" means "back to the
 * placeholder's default" - empty string, not NULL/unset - once that
 * placeholder has been created. In practice this is harmless, because
 * every real RLS-protected access goes through THIS aspect, which always
 * sets a fresh, correct value before touching anything. It only bites code
 * that queries an RLS table OUTSIDE of a @TenantScoped method on a
 * connection that has previously had this GUC set - e.g. a test's own
 * unscoped cleanup SQL - which sees an empty-string cast failure instead
 * of the "no rows match" you'd expect from a truly-unset session variable.
 */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class TenantRlsAspect {

    @PersistenceContext
    private EntityManager entityManager;

    @Around("@annotation(com.imperial.common.tenant.TenantScoped) || @within(com.imperial.common.tenant.TenantScoped)")
    public Object applyTenantContext(ProceedingJoinPoint joinPoint) throws Throwable {
        var tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "No tenant in TenantContext for @TenantScoped method " + joinPoint.getSignature());
        }

        // Use the JPA transaction's connection. A standalone JdbcTemplate
        // can acquire a different pooled connection, leaving Hibernate's
        // actual RLS-protected statements without this local setting.
        entityManager.createNativeQuery("SELECT set_config('app.current_tenant', :tenantId, true)")
                .setParameter("tenantId", tenantId.toString())
                .getSingleResult();

        return joinPoint.proceed();
    }
}
