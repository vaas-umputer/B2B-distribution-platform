package com.imperial.credit.dto;

import java.math.BigDecimal;

public record ManualOverrideRequest(BigDecimal newLimit, String overrideReason) {
}
