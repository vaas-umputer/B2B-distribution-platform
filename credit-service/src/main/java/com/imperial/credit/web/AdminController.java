package com.imperial.credit.web;

import com.imperial.credit.domain.LimitIncreaseStatus;
import com.imperial.credit.dto.AlertView;
import com.imperial.credit.dto.LimitIncreaseRequestView;
import com.imperial.credit.dto.MonthlyReportView;
import com.imperial.credit.service.AdminReadService;
import com.imperial.credit.service.LimitIncreaseRequestService;
import com.imperial.credit.service.OverrideBelowOutstandingException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Every endpoint here is cross-tenant by nature (an admin needs to see
 * across every tenant at once) - gated at the method level with
 * @PreAuthorize so it's explicit per-endpoint, not just "this whole
 * controller happens to be mounted somewhere private." Full RBAC is an
 * explicit v1 non-goal in the plan; this is the simplest thing that makes
 * "admin-only" actually mean something: a real role check, backed by one
 * hardcoded credential pair (see AuthController.adminLogin), not a UI
 * convention someone could bypass by just calling the API directly.
 */
@RestController
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AdminReadService adminReadService;
    private final LimitIncreaseRequestService limitIncreaseRequestService;

    public AdminController(AdminReadService adminReadService, LimitIncreaseRequestService limitIncreaseRequestService) {
        this.adminReadService = adminReadService;
        this.limitIncreaseRequestService = limitIncreaseRequestService;
    }

    @GetMapping("/alerts")
    public List<AlertView> alerts() {
        return adminReadService.listAlerts();
    }

    @GetMapping("/utilization")
    public List<AdminReadService.UtilizationView> utilization() {
        return adminReadService.listUtilization();
    }

    @GetMapping("/reports/monthly/latest")
    public MonthlyReportView latestMonthlyReport() {
        return adminReadService.latestMonthlyReport()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "no monthly report compiled yet"));
    }

    @GetMapping("/limit-increase-requests")
    public List<LimitIncreaseRequestView> limitIncreaseRequests(
            @RequestParam(required = false) LimitIncreaseStatus status) {
        return limitIncreaseRequestService.listForAdmin(status);
    }

    @PostMapping("/limit-increase-requests/{id}/approve")
    public LimitIncreaseRequestView approve(@PathVariable UUID id) {
        try {
            return LimitIncreaseRequestView.from(limitIncreaseRequestService.approve(id, "admin"));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(CONFLICT, e.getMessage());
        } catch (OverrideBelowOutstandingException e) {
            // approve() funds the request through the same overrideLimit()
            // path CreditController.overrideLimit uses - without this catch,
            // approving a stale request (outstanding grew past the
            // requested amount since it was submitted) would leak as a
            // raw 500 instead of a clean 409, the same case CreditController
            // already handles correctly for the manual-override endpoint.
            throw new ResponseStatusException(CONFLICT, e.getMessage());
        }
    }

    @PostMapping("/limit-increase-requests/{id}/reject")
    public LimitIncreaseRequestView reject(@PathVariable UUID id) {
        try {
            return LimitIncreaseRequestView.from(limitIncreaseRequestService.reject(id, "admin"));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(CONFLICT, e.getMessage());
        }
    }
}
