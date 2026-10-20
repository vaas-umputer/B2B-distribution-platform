package com.imperial.common.tenant;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a @Transactional repository/service method (or a whole class of them)
 * whose queries must be scoped to TenantContext's current tenant via Postgres
 * RLS. TenantRlsAspect intercepts exactly these methods - every other
 * @Transactional method in the app is left alone, so RLS plumbing never
 * silently applies (or fails to apply) where nobody asked for it.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface TenantScoped {
}
