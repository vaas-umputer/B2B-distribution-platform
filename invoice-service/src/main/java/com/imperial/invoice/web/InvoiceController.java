package com.imperial.invoice.web;

import com.imperial.common.dto.IssueInvoiceRequest;
import com.imperial.common.dto.IssueInvoiceResponse;
import com.imperial.common.tenant.TenantContext;
import com.imperial.invoice.domain.Invoice;
import com.imperial.invoice.service.InvoiceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.List;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/invoices")
public class InvoiceController {

    private final InvoiceService invoiceService;

    public InvoiceController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @PostMapping
    public IssueInvoiceResponse issue(@Valid @RequestBody IssueInvoiceRequest request) {
        if (!request.tenantId().equals(TenantContext.get())) {
            throw new ResponseStatusException(BAD_REQUEST, "tenantId does not match authenticated caller");
        }
        var invoice = invoiceService.issue(request.orderId(), request.tenantId(), request.amount(), request.terms());
        return new IssueInvoiceResponse(invoice.getId(), invoice.getStatus().name());
    }

    /**
     * Stand-in for "admin re-enters the Tally reference" (plan's v1
     * invoicing flow) - any caller holding a valid JWT for this invoice's
     * own tenant, since full RBAC/admin-only enforcement is an explicit
     * v1 non-goal.
     */
    @PostMapping("/{id}/mark-paid")
    public IssueInvoiceResponse markPaid(@PathVariable UUID id) {
        try {
            Invoice invoice = invoiceService.markPaid(id);
            return new IssueInvoiceResponse(invoice.getId(), invoice.getStatus().name());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(NOT_FOUND, e.getMessage());
        }
    }

    @GetMapping
    public List<InvoiceView> list() {
        return invoiceService.listInvoices().stream().map(InvoiceView::from).toList();
    }

    public record InvoiceView(UUID id, UUID orderId, java.math.BigDecimal amount, String terms,
                              String status, java.time.LocalDate dueDate) {
        static InvoiceView from(Invoice invoice) {
            return new InvoiceView(invoice.getId(), invoice.getOrderId(), invoice.getAmount(), invoice.getTerms(),
                    invoice.getStatus().name(), invoice.getDueDate());
        }
    }
}
