package com.imperial.credit.service;

import com.imperial.common.dto.LockCreditResponse;
import com.imperial.common.idempotency.IdempotencyGuard;
import com.imperial.common.tenant.TenantScoped;
import com.imperial.credit.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * The real two-level check from the plan's "Order-time enforcement" section,
 * replacing Phase 1's stub (approve-if-under-100000). Both TenantAvailable
 * AND PoolAvailable must cover the order - a tenant can be comfortably
 * within their own limit while the platform-wide pool is still the binding
 * constraint, and vice versa.
 *
 * <p><b>Lock ordering.</b> pool_balance is locked FIRST, then the specific
 * tenant's credit_accounts row, in every method here, with no exceptions.
 * Two concurrent orders - say tenant A's order and tenant B's order -
 * always touch the SAME pool_balance row plus their OWN distinct account
 * row. If one thread locked account-then-pool while another locked
 * pool-then-account, two threads could each hold one resource and wait on
 * the other forever (a classic lock-ordering deadlock). Always acquiring
 * pool before account, everywhere, makes that structurally impossible: at
 * most one transaction can ever be holding the pool lock at a time, so
 * there's nothing for a second transaction to deadlock against.
 */
@Service
public class CreditCheckService {

    private static final Logger log = LoggerFactory.getLogger(CreditCheckService.class);

    private final CreditAccountRepository creditAccountRepository;
    private final PoolBalanceRepository poolBalanceRepository;
    private final CreditLockRepository creditLockRepository;
    private final IdempotencyGuard idempotencyGuard;
    private final CreditOverrideWriter creditOverrideWriter;

    public CreditCheckService(CreditAccountRepository creditAccountRepository,
                               PoolBalanceRepository poolBalanceRepository,
                               CreditLockRepository creditLockRepository,
                               IdempotencyGuard idempotencyGuard,
                               CreditOverrideWriter creditOverrideWriter) {
        this.creditAccountRepository = creditAccountRepository;
        this.poolBalanceRepository = poolBalanceRepository;
        this.creditLockRepository = creditLockRepository;
        this.idempotencyGuard = idempotencyGuard;
        this.creditOverrideWriter = creditOverrideWriter;
    }

    @TenantScoped
    @Transactional
    public LockCreditResponse lockCredit(UUID tenantId, UUID orderId, BigDecimal orderValue) {
        // A lock already existing for this order IS the idempotency check -
        // a retried call just hands back the same answer, touching nothing.
        var existingLock = creditLockRepository.findByOrderId(orderId);
        if (existingLock.isPresent()) {
            log.info("lockCredit retry for order {} - returning existing lock {}", orderId, existingLock.get().getId());
            return LockCreditResponse.approved(existingLock.get().getId());
        }

        PoolBalance pool = poolBalanceRepository.lockTheSingleton();
        CreditAccount account = creditAccountRepository.lockByTenantId(tenantId).orElse(null);

        if (account == null) {
            return LockCreditResponse.rejected("tenant has no credit account provisioned yet");
        }
        if (account.isThrottled()) {
            return LockCreditResponse.rejected("tenant is throttled (an invoice is overdue)");
        }

        BigDecimal availableWorkingCapital = CreditMath.availableWorkingCapital(
                pool.getCash(), pool.getCommittedPayables(), pool.getOperatingBuffer());
        BigDecimal allocatablePool = CreditMath.allocatablePool(availableWorkingCapital, pool.getBufferPercent());
        BigDecimal tenantAvailable = CreditMath.tenantAvailable(account.getCreditLimit(), account.getOutstanding());
        BigDecimal poolAvailable = CreditMath.poolAvailable(allocatablePool, pool.getTotalOutstanding());

        if (!CreditMath.orderAllowed(orderValue, tenantAvailable, poolAvailable)) {
            log.info("lockCredit REJECTED for order {} - orderValue={} tenantAvailable={} poolAvailable={}",
                    orderId, orderValue, tenantAvailable, poolAvailable);
            return LockCreditResponse.rejected(String.format(
                    "order value %s exceeds available capacity (tenant available=%s, pool available=%s)",
                    orderValue, tenantAvailable, poolAvailable));
        }

        CreditLock lock = new CreditLock(UUID.randomUUID(), tenantId, orderId, orderValue, LockStatus.LOCKED, Instant.now());
        creditLockRepository.save(lock);

        account.setOutstanding(account.getOutstanding().add(orderValue));
        creditAccountRepository.save(account);

        pool.setTotalOutstanding(pool.getTotalOutstanding().add(orderValue));
        poolBalanceRepository.save(pool);

        log.info("lockCredit LOCKED {} for order {} - amount={} tenantAvailable={} poolAvailable={}",
                lock.getId(), orderId, orderValue, tenantAvailable, poolAvailable);
        return LockCreditResponse.approved(lock.getId());
    }

    /** Explicit HTTP compensation path: an order's stock check failed after credit was already locked. */
    @TenantScoped
    @Transactional
    public void release(UUID lockId) {
        String operationKey = "release:" + lockId;
        if (idempotencyGuard.alreadyProcessed(operationKey)) {
            return;
        }
        CreditLock lock = creditLockRepository.findById(lockId)
                .orElseThrow(() -> new NoSuchElementException("credit lock not found: " + lockId));
        if (lock.getStatus() == LockStatus.LOCKED) {
            applyReleaseOrConsume(lock, LockStatus.RELEASED);
        }
        idempotencyGuard.markProcessed(operationKey);
    }

    /**
     * The async/event path: Invoice Service's invoice.paid message arrives
     * here (via CreditEventListener, which sets TenantContext from the
     * event before calling this - there's no HTTP request/JWT to read it
     * from). Rotates this lock's amount back into both the tenant's and
     * the pool's available capacity, same as release(), but ends in
     * CONSUMED rather than RELEASED since this is the normal, successful
     * end of the order's life, not a compensation.
     */
    @TenantScoped
    @Transactional
    public void consume(UUID orderId, String operationKey) {
        if (idempotencyGuard.alreadyProcessed(operationKey)) {
            return;
        }
        creditLockRepository.findByOrderId(orderId).ifPresent(lock -> {
            if (lock.getStatus() == LockStatus.LOCKED) {
                applyReleaseOrConsume(lock, LockStatus.CONSUMED);
            }
        });
        idempotencyGuard.markProcessed(operationKey);
    }

    @TenantScoped
    @Transactional
    public void throttleTenant(UUID tenantId, String operationKey) {
        if (idempotencyGuard.alreadyProcessed(operationKey)) {
            return;
        }
        creditAccountRepository.lockByTenantId(tenantId).ifPresent(account -> {
            account.setThrottled(true);
            creditAccountRepository.save(account);
            log.info("tenant {} THROTTLED (overdue invoice)", tenantId);
        });
        idempotencyGuard.markProcessed(operationKey);
    }

    private void applyReleaseOrConsume(CreditLock lock, LockStatus targetStatus) {
        PoolBalance pool = poolBalanceRepository.lockTheSingleton();
        CreditAccount account = creditAccountRepository.lockByTenantId(lock.getTenantId())
                .orElseThrow(() -> new IllegalStateException("credit account missing for tenant " + lock.getTenantId()));

        account.setOutstanding(account.getOutstanding().subtract(lock.getAmount()));
        creditAccountRepository.save(account);

        pool.setTotalOutstanding(pool.getTotalOutstanding().subtract(lock.getAmount()));
        poolBalanceRepository.save(pool);

        lock.setStatus(targetStatus);
        creditLockRepository.save(lock);

        log.info("credit {} {} for order {} - amount={}", targetStatus, lock.getId(), lock.getOrderId(), lock.getAmount());
    }

    /**
     * Admin provisioning stand-in for Phase 2 (the real review queue is
     * Phase 3's job): sets a tenant's credit_accounts row directly to
     * whatever limit the caller passes - typically a number the admin
     * read off a prior /credit/{tenantId}/risk-score call, per the plan's
     * "admin sees the suggestion... and sets the actual CreditLimit."
     */
    @TenantScoped
    @Transactional
    public CreditAccount provisionAccount(UUID tenantId, BigDecimal creditLimit, String setBy) {
        if (creditLimit == null || creditLimit.signum() < 0) {
            throw new IllegalArgumentException("creditLimit must be a non-negative amount");
        }
        // Re-provisioning an EXISTING account must go through the same
        // never-below-outstanding safeguard overrideLimit() enforces -
        // without this, an admin could re-provision a tenant with an
        // existing balance down to a limit below what's currently owed,
        // the same corruption overrideLimit() exists specifically to
        // prevent. A brand-new account has no outstanding balance yet, so
        // this is a no-op for the first-provisioning case.
        var existing = creditAccountRepository.lockByTenantId(tenantId);
        BigDecimal oldLimit = existing.map(CreditAccount::getCreditLimit).orElse(null);
        if (existing.isPresent() && !CreditMath.isOverrideAllowed(creditLimit, existing.get().getOutstanding())) {
            throw new OverrideBelowOutstandingException(creditLimit, existing.get().getOutstanding());
        }

        CreditAccount account = existing
                .map(acc -> {
                    acc.setCreditLimit(creditLimit);
                    return acc;
                })
                .orElseGet(() -> new CreditAccount(tenantId, creditLimit));
        creditAccountRepository.save(account);

        creditOverrideWriter.record(tenantId, oldLimit, creditLimit, "admin provisioning", setBy);
        return account;
    }

    /**
     * Admin-triggered recalculation using the tenant's STORED onboarding
     * inputs (credit_risk_scores) - enforces the plan's explicit safeguard:
     * a recompute must never cut a limit below what's currently owed.
     */
    @TenantScoped
    @Transactional
    public CreditAccount recompute(UUID tenantId, RiskScoreResult freshScore, String setBy) {
        CreditAccount account = creditAccountRepository.lockByTenantId(tenantId)
                .orElseThrow(() -> new NoSuchElementException("tenant has no credit account provisioned yet"));

        BigDecimal oldLimit = account.getCreditLimit();
        BigDecimal newLimit = CreditMath.applyNewLimitSafeguard(freshScore.suggestedLimitAfterCeiling(), account.getOutstanding());

        account.setCreditLimit(newLimit);
        creditAccountRepository.save(account);

        creditOverrideWriter.record(tenantId, oldLimit, newLimit, "scheduled/admin recompute", setBy);
        return account;
    }

    /**
     * Phase 3's manual override AND limit-increase-request approval both
     * fund through this one method - "keeping one audit trail for both
     * paths" per the plan. Unlike recompute(), this REJECTS outright
     * (throws) rather than clamping if newLimit would sit below what's
     * currently owed - the plan's exit criteria are explicit that this
     * specific path must not silently adjust the number.
     *
     * <p>Callers (CreditController's PATCH endpoint, and
     * LimitIncreaseRequestService's approve path) are expected to have
     * already set TenantContext to the TARGET tenant - never the admin
     * caller's own JWT tenant claim, which for an admin identity means
     * nothing tenant-wise. See AuthController's admin-login Javadoc.
     */
    @TenantScoped
    @Transactional
    public CreditAccount overrideLimit(UUID tenantId, BigDecimal newLimit, String overrideReason, String setBy) {
        CreditAccount account = creditAccountRepository.lockByTenantId(tenantId)
                .orElseThrow(() -> new NoSuchElementException("tenant has no credit account provisioned yet"));

        if (!CreditMath.isOverrideAllowed(newLimit, account.getOutstanding())) {
            throw new OverrideBelowOutstandingException(newLimit, account.getOutstanding());
        }

        BigDecimal oldLimit = account.getCreditLimit();
        account.setCreditLimit(newLimit);
        creditAccountRepository.save(account);

        creditOverrideWriter.record(tenantId, oldLimit, newLimit, overrideReason, setBy);
        return account;
    }
}
