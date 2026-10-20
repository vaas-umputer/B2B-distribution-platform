package com.imperial.credit.dto;

import java.math.BigDecimal;

public record SubmitLimitIncreaseRequest(BigDecimal requestedAmount, String reason) {
}
