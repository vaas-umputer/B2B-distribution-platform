package com.imperial.invoice.service;

import com.imperial.common.tenant.TenantScoped;
import com.imperial.invoice.domain.Invoice;
import com.imperial.invoice.domain.InvoiceRepository;
import com.imperial.invoice.domain.InvoiceStatus;
import com.imperial.invoice.domain.InvoiceTransitions;
import com.imperial.invoice.messaging.InvoiceEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.List;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class InvoiceService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);

    private final InvoiceRepository invoiceRepository;
    private final InvoiceEventPublisher invoiceEventPublisher;

    public InvoiceService(InvoiceRepository invoiceRepository, InvoiceEventPublisher invoiceEventPublisher) {
        this.invoiceRepository = invoiceRepository;
        this.invoiceEventPublisher = invoiceEventPublisher;
    }

    @TenantScoped
    @Transactional
    public Invoice issue(UUID orderId, UUID tenantId, BigDecimal amount, String terms) {
        LocalDate dueDate = computeDueDate(terms);
        Invoice invoice = new Invoice(
                UUID.randomUUID(), orderId, tenantId, amount, terms, InvoiceStatus.ISSUED, dueDate, Instant.now());
        invoiceRepository.save(invoice);
        log.info("invoice ISSUED {} for order {} - amount={} dueDate={}", invoice.getId(), orderId, amount, dueDate);
        return invoice;
    }

    /**
     * No role check beyond "a valid JWT for this invoice's own tenant" -
     * full RBAC/admin-only enforcement is an explicit v1 non-goal in the
     * plan. This is a Phase 2 stand-in for "the admin re-enters the Tally
     * reference" flow, not a finished admin feature.
     */
    @TenantScoped
    @Transactional
    public Invoice markPaid(UUID invoiceId) {
        Invoice invoice = requireInvoice(invoiceId);
        transition(invoice, InvoiceStatus.PAID);
        invoiceEventPublisher.publishPaid(invoice);
        log.info("invoice PAID {} for order {}", invoice.getId(), invoice.getOrderId());
        return invoice;
    }

    @TenantScoped
    @Transactional(readOnly = true)
    public List<Invoice> listInvoices() {
        // Tenant filtering is structurally enforced by the table's RLS policy.
        return invoiceRepository.findAllByOrderByCreatedAtDesc();
    }

    @TenantScoped
    @Transactional
    public void markOverdue(UUID invoiceId) {
        Invoice invoice = requireInvoice(invoiceId);
        if (invoice.getStatus() != InvoiceStatus.ISSUED) {
            return; // already moved on (paid, or already overdue) - the sweep is allowed to be a little stale
        }
        transition(invoice, InvoiceStatus.OVERDUE);
        invoiceEventPublisher.publishOverdue(invoice);
        log.info("invoice OVERDUE {} for order {}", invoice.getId(), invoice.getOrderId());
    }

    private Invoice requireInvoice(UUID invoiceId) {
        return invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new NoSuchElementException("invoice not found: " + invoiceId));
    }

    private void transition(Invoice invoice, InvoiceStatus to) {
        if (!InvoiceTransitions.isAllowed(invoice.getStatus(), to)) {
            throw new ResponseStatusException(CONFLICT,
                    "illegal invoice transition " + invoice.getStatus() + " -> " + to);
        }
        invoice.setStatus(to);
        invoiceRepository.save(invoice);
    }

    private LocalDate computeDueDate(String terms) {
        int days = switch (terms) {
            case "NET_15" -> 15;
            case "NET_60" -> 60;
            default -> 30; // NET_30 and anything unrecognized - a safe default, not a silent full exemption
        };
        return LocalDate.now().plusDays(days);
    }
}
