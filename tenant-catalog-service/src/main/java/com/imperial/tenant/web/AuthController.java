package com.imperial.tenant.web;

import com.imperial.common.security.JwtService;
import com.imperial.common.tenant.TenantContext;
import com.imperial.tenant.dto.AdminLoginRequest;
import com.imperial.tenant.dto.LoginRequest;
import com.imperial.tenant.dto.LoginResponse;
import com.imperial.tenant.service.TenantService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final String ROLE_TENANT = "TENANT";
    private static final String ROLE_ADMIN = "ADMIN";

    /**
     * Fixed, meaningless identity for the "subject" claim of an admin JWT.
     * It is never used as a real tenant id anywhere - every admin-only
     * endpoint explicitly operates on a TARGET tenant given in the URL
     * (and sets TenantContext to THAT tenant before touching anything
     * RLS-protected), never on whatever this constant happens to be.
     * JwtService's signature requires a UUID subject; this is just
     * satisfying that shape for an identity that isn't tenant-shaped at all.
     */
    private static final UUID ADMIN_PRINCIPAL_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final TenantService tenantService;
    private final JwtService jwtService;
    private final String adminUsername;
    private final String adminPassword;

    public AuthController(TenantService tenantService, JwtService jwtService,
                           @Value("${admin.username:admin}") String adminUsername,
                           @Value("${admin.password:admin-dev-secret-CHANGE-ME}") String adminPassword) {
        this.tenantService = tenantService;
        this.jwtService = jwtService;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        // See TenantService.findById's Javadoc: setting the claimed id as the
        // tenant context before looking it up means the RLS policy itself
        // enforces "you can only ever confirm your own identity" here.
        var tenant = tenantService.authenticate(request.username(), request.password())
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "invalid username or password"));
        String role = tenant.getStatus() == com.imperial.tenant.domain.TenantStatus.ACTIVE ? ROLE_TENANT : "PENDING";
        return new LoginResponse(jwtService.generateToken(tenant.getId(), role));
    }

    /**
     * Phase 3 stand-in, not a real admin user system: one credential pair
     * from config (env vars in practice), compared directly - no password
     * hashing, no user table, no "Full RBAC permission editor" (an
     * explicit v1 non-goal in the plan). This is enough to demonstrate
     * role-gated endpoints (@PreAuthorize("hasRole('ADMIN')")) without
     * building an admin identity system the plan never asked for.
     */
    @PostMapping("/admin-login")
    public LoginResponse adminLogin(@RequestBody AdminLoginRequest request) {
        if (!adminUsername.equals(request.username()) || !adminPassword.equals(request.password())) {
            throw new ResponseStatusException(UNAUTHORIZED, "invalid admin credentials");
        }
        String token = jwtService.generateToken(ADMIN_PRINCIPAL_ID, ROLE_ADMIN);
        return new LoginResponse(token);
    }
}
