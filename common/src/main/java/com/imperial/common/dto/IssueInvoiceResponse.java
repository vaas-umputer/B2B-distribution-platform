package com.imperial.common.dto;

import java.util.UUID;

public record IssueInvoiceResponse(
        UUID invoiceId,
        String status
) {
}
