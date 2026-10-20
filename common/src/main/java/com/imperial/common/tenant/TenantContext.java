package com.imperial.common.tenant;

import java.util.UUID;

/**
 * The current request's tenant, read by TenantRlsAspect and (from Phase 1)
 * populated by the JWT auth filter from the token's tenant_id claim.
 * ThreadLocal because a servlet request is handled start-to-finish on one
 * thread; it must be cleared at the end of every request or a thread borrowed
 * from the container's pool could leak a tenant id into the next unrelated
 * request that happens to land on the same thread.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(UUID tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static UUID get() {
        return CURRENT_TENANT.get();
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
