package com.imperial.credit.web;

import com.imperial.common.dto.LockCreditRequest;
import com.imperial.common.dto.LockCreditResponse;
import com.imperial.common.tenant.TenantContext;
import com.imperial.credit.domain.OnboardingData;
import com.imperial.credit.dto.*;
import com.imperial.credit.service.CreditCheckService;
import com.imperial.credit.service.CreditRiskScoreService;
import com.imperial.credit.service.LimitIncreaseRequestService;
import com.imperial.credit.service.OverrideBelowOutstandingException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.NoSuchElementException;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/credit")
public class CreditController {

    private final CreditCheckService creditCheckService;
    private final CreditRiskScoreService creditRiskScoreService;
    private final LimitIncreaseRequestService limitIncreaseRequestService;

    public CreditController(CreditCheckService creditCheckService, CreditRiskScoreService creditRiskScoreService,
                             LimitIncreaseRequestService limitIncreaseRequestService) {
        this.creditCheckService = creditCheckService;
        this.creditRiskScoreService = creditRiskScoreService;
        this.limitIncreaseRequestService = limitIncreaseRequestService;
    }

    @PostMapping("/lock")
    public LockCreditResponse lock(@RequestBody LockCreditRequest request) {
        UUID callerTenantId = TenantContext.get();
        if (!request.tenantId().equals(callerTenantId)) {
            throw new ResponseStatusException(BAD_REQUEST, "tenantId does not match authenticated caller");
        }
        return creditCheckService.lockCredit(callerTenantId, request.orderId(), request.orderValue());
    }

    @PostMapping("/release/{lockId}")
    public void release(@PathVariable UUID lockId) {
        try {
            creditCheckService.release(lockId);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(NOT_FOUND, e.getMessage());
        }
    }

    /** Pure suggestion - never applied by itself. See RiskScoringService's Javadoc. */
    @PostMapping("/{tenantId}/risk-score")
    public RiskScoreView score(@PathVariable UUID tenantId, @RequestBody RiskScoreRequest request) {
        requireSelf(tenantId);
        var data = new OnboardingData(request.declaredMonthlyVolume(), request.businessVintageYears(),
                request.collateralOffered(), request.relationshipCategoryPoints());
        return RiskScoreView.from(creditRiskScoreService.scoreAndPersist(tenantId, data));
    }

    /** Phase 2 stand-in for the admin review queue (Phase 3): the caller decides the number, this just applies it. */
    @PostMapping("/{tenantId}/accounts")
    public CreditAccountView provision(@PathVariable UUID tenantId, @RequestBody ProvisionAccountRequest request) {
        requireSelf(tenantId);
        return CreditAccountView.from(creditCheckService.provisionAccount(tenantId, request.creditLimit(), "self-service"));
    }

    @PostMapping("/{tenantId}/recompute")
    public CreditAccountView recompute(@PathVariable UUID tenantId) {
        requireSelf(tenantId);
        try {
            var freshScore = creditRiskScoreService.recomputeFromStoredInputs(tenantId);
            return CreditAccountView.from(creditCheckService.recompute(tenantId, freshScore, "self-service"));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(NOT_FOUND, e.getMessage());
        }
    }

    /**
     * Admin-only. The admin's own JWT subject (ADMIN_PRINCIPAL_ID - see
     * AuthController) isn't a real tenant, so requireSelf() would never
     * match here and shouldn't be used - this explicitly stamps
     * TenantContext to the TARGET tenant from the path before touching
     * anything RLS-protected, the same pattern LimitIncreaseRequestService
     * uses for approve/reject.
     */
    @PatchMapping("/{tenantId}/limit")
    @PreAuthorize("hasRole('ADMIN')")
    public CreditAccountView overrideLimit(@PathVariable UUID tenantId, @RequestBody ManualOverrideRequest request) {
        TenantContext.set(tenantId);
        try {
            var account = creditCheckService.overrideLimit(tenantId, request.newLimit(), request.overrideReason(), "admin");
            return CreditAccountView.from(account);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(NOT_FOUND, e.getMessage());
        } catch (OverrideBelowOutstandingException e) {
            throw new ResponseStatusException(CONFLICT, e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    /** Tenant role: a tenant submits a request for itself, landing in the admin review queue. */
    @PostMapping("/{tenantId}/limit-increase-requests")
    public LimitIncreaseRequestView submitLimitIncreaseRequest(
            @PathVariable UUID tenantId, @RequestBody SubmitLimitIncreaseRequest request) {
        requireSelf(tenantId);
        var created = limitIncreaseRequestService.submit(tenantId, request.requestedAmount(), request.reason());
        return LimitIncreaseRequestView.from(created);
    }

    private void requireSelf(UUID tenantId) {
        if (!tenantId.equals(TenantContext.get())) {
            throw new ResponseStatusException(BAD_REQUEST, "tenantId does not match authenticated caller");
        }
    }
}
