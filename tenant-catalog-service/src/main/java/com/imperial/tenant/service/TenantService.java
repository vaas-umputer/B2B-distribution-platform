package com.imperial.tenant.service;

import com.imperial.common.tenant.TenantScoped;
import com.imperial.tenant.domain.Tenant;
import com.imperial.tenant.domain.TenantRepository;
import com.imperial.tenant.domain.TenantStatus;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class TenantService {

    private final TenantRepository tenantRepository;

    public TenantService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    /**
     * Deliberately NOT @TenantScoped: no tenant context can exist yet for an
     * identity that doesn't exist until this call returns. The INSERT-only
     * RLS policy on tenants is what makes this safe to run without one.
     */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    @PersistenceContext private EntityManager entityManager;

    @Transactional
    public Tenant onboard(String name, String username, String password, String email, String phone) {
        if (username == null || username.isBlank() || password == null || password.length() < 8) throw new IllegalArgumentException("username and password (8+ chars) are required");

        // Same reason as authenticate(): findByUsername's result is gated by
        // the tenants_self_select RLS policy's "username = current_setting
        // ('app.login_username', ...)" clause (V2 migration). Without
        // setting it here first, this lookup always sees zero rows - RLS
        // hides the very duplicate we're trying to catch - so the check
        // silently passes and the INSERT below fails instead, with an
        // unhandled DataIntegrityViolationException (-> 500) rather than
        // a clean "username is already taken" (-> 400).
        entityManager.createNativeQuery("SELECT set_config('app.login_username', :username, true)")
                .setParameter("username", username).getSingleResult();
        if (tenantRepository.findByUsername(username).isPresent()) throw new IllegalArgumentException("username is already taken");

        Tenant tenant = new Tenant(UUID.randomUUID(), name, username, passwordEncoder.encode(password), email, phone, TenantStatus.PENDING_REVIEW, Instant.now());
        try {
            return tenantRepository.save(tenant);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Belt-and-suspenders for the race the app-level check above
            // can't close by itself: two concurrent onboarding requests for
            // the same username can both pass the check above before
            // either commits. The UNIQUE constraint is the real guarantee;
            // this just turns its violation into the same clean error
            // instead of a stack trace reaching the client.
            throw new IllegalArgumentException("username is already taken");
        }
    }

    @Transactional(readOnly = true)
    public Optional<Tenant> authenticate(String username, String password) {
        entityManager.createNativeQuery("SELECT set_config('app.login_username', :username, true)")
                .setParameter("username", username).getSingleResult();
        return tenantRepository.findByUsername(username).filter(t -> passwordEncoder.matches(password, t.getPasswordHash()));
    }

    /**
     * Used both for the authenticated GET /tenants/{id} endpoint and, with a
     * twist, for login: AuthController sets TenantContext to the *claimed*
     * tenant id before calling this, so the RLS policy naturally restricts
     * the lookup to exactly that one row. A caller can never use login to
     * probe whether some other tenant id exists - the row-security check is
     * identical to the one an authenticated request gets, not a bypass.
     */
    @TenantScoped
    @Transactional(readOnly = true)
    public Optional<Tenant> findById(UUID id) {
        return tenantRepository.findById(id);
    }
}
