package com.imperial.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record OrderLineRequest(@NotBlank String sku, @Positive int qty, @NotNull @Positive BigDecimal unitPrice) {
}
