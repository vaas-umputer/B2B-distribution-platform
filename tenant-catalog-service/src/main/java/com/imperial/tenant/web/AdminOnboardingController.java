package com.imperial.tenant.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/** Admin-only read/write boundary. It deliberately uses svc_system because
 * onboarding review is the one tenant-service workflow that must span tenants. */
@RestController
@RequestMapping("/admin/onboarding")
@PreAuthorize("hasRole('ADMIN')")
public class AdminOnboardingController {
    private final JdbcTemplate system;
    public AdminOnboardingController(@Value("${spring.datasource.url}") String url,
                                     @Value("${system.datasource.username:svc_system}") String user,
                                     @Value("${system.datasource.password:svc_system}") String password) {
        var ds = new DriverManagerDataSource(url, user, password);
        this.system = new JdbcTemplate(ds);
    }
    @GetMapping
    public List<OnboardingView> list(@RequestParam(defaultValue = "PENDING_REVIEW") String status) {
        return system.query("SELECT id,name,username,email,phone,status,created_at FROM tenants WHERE status=? ORDER BY created_at", (rs,i) ->
            new OnboardingView(UUID.fromString(rs.getString("id")), rs.getString("name"), rs.getString("username"), rs.getString("email"), rs.getString("phone"), rs.getString("status"), rs.getTimestamp("created_at").toInstant()), status);
    }
    @GetMapping("/{tenantId}/documents")
    public List<DocumentView> documents(@PathVariable UUID tenantId) {
        return system.query("SELECT id,document_type,original_filename,review_status,review_note FROM tenant_documents WHERE tenant_id=? ORDER BY uploaded_at", (rs,i) ->
            new DocumentView(UUID.fromString(rs.getString("id")),rs.getString("document_type"),rs.getString("original_filename"),rs.getString("review_status"),rs.getString("review_note")), tenantId);
    }
    @PostMapping("/{tenantId}/approve") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(@PathVariable UUID tenantId, @RequestBody ReviewRequest request) {
        int updated = system.update("UPDATE tenants SET status='ACTIVE' WHERE id=? AND status='PENDING_REVIEW'", tenantId);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "tenant does not exist or is not pending review");
        }
        system.update("UPDATE tenant_documents SET review_status='ACCEPTED', review_note=? WHERE tenant_id=?", request.note(), tenantId);
    }
    @PostMapping("/{tenantId}/reject") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@PathVariable UUID tenantId, @RequestBody ReviewRequest request) {
        // Mirrors approve()'s guard: only a still-pending tenant can be
        // rejected, so an already-ACTIVE tenant can't be silently flipped
        // to REJECTED, and a nonexistent id gets a clean 409 instead of a
        // no-op 204 a caller can't distinguish from success.
        int updated = system.update("UPDATE tenants SET status='REJECTED' WHERE id=? AND status='PENDING_REVIEW'", tenantId);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "tenant does not exist or is not pending review");
        }
        system.update("UPDATE tenant_documents SET review_status='REJECTED', review_note=? WHERE tenant_id=?", request.note(), tenantId);
    }
    public record ReviewRequest(String note) {}
    public record OnboardingView(UUID id,String name,String username,String email,String phone,String status,java.time.Instant createdAt) {}
    public record DocumentView(UUID id,String type,String filename,String status,String note) {}
}
