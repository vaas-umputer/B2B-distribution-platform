package com.imperial.credit.service;

import com.imperial.common.tenant.TenantScoped;
import com.imperial.credit.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Glue between the pure RiskScoringService (no DB) and persistence: scores
 * a tenant and remembers the inputs/result under credit_risk_scores, so a
 * later recompute can re-run the SAME formula without the caller having to
 * resend the original onboarding data.
 */
@Service
public class CreditRiskScoreService {

    private final RiskScoringService riskScoringService;
    private final CreditRiskScoreRepository creditRiskScoreRepository;

    public CreditRiskScoreService(RiskScoringService riskScoringService,
                                   CreditRiskScoreRepository creditRiskScoreRepository) {
        this.riskScoringService = riskScoringService;
        this.creditRiskScoreRepository = creditRiskScoreRepository;
    }

    @TenantScoped
    @Transactional
    public RiskScoreResult scoreAndPersist(UUID tenantId, OnboardingData data) {
        RiskScoreResult result = riskScoringService.computeScore(data);

        CreditRiskScore record = creditRiskScoreRepository.findById(tenantId).orElseGet(() ->
                new CreditRiskScore(tenantId, data.declaredMonthlyVolume(), data.businessVintageYears(),
                        data.collateralOffered(), data.relationshipCategoryPoints(),
                        result.score(), result.suggestedLimitAfterCeiling(), Instant.now()));
        record.update(data.declaredMonthlyVolume(), data.businessVintageYears(), data.collateralOffered(),
                data.relationshipCategoryPoints(), result.score(), result.suggestedLimitAfterCeiling(), Instant.now());
        creditRiskScoreRepository.save(record);

        return result;
    }

    /** Admin-facing: the stored suggestion from the tenant's own risk-score submission, if any - lets the admin dashboard prefill a sensible starting limit instead of asking the admin to guess a number. */
    @TenantScoped
    @Transactional(readOnly = true)
    public java.util.Optional<CreditRiskScore> findStored(UUID tenantId) {
        return creditRiskScoreRepository.findById(tenantId);
    }

    @TenantScoped
    @Transactional(readOnly = true)
    public RiskScoreResult recomputeFromStoredInputs(UUID tenantId) {
        CreditRiskScore stored = creditRiskScoreRepository.findById(tenantId)
                .orElseThrow(() -> new NoSuchElementException("no stored risk score inputs for tenant " + tenantId));
        OnboardingData data = new OnboardingData(
                stored.getDeclaredVolume(), stored.getBusinessVintage(), stored.getCollateral(), stored.getRelationshipCategory());
        return riskScoringService.computeScore(data);
    }
}
