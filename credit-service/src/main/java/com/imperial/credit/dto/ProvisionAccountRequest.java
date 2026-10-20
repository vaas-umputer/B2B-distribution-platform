package com.imperial.credit.dto;

import java.math.BigDecimal;

/** What the admin actually typed in, after looking at a prior /risk-score suggestion - never applied automatically. */
public record ProvisionAccountRequest(BigDecimal creditLimit) {
}
