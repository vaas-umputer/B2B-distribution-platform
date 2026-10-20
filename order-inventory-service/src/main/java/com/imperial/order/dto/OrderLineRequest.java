package com.imperial.order.dto;

import java.math.BigDecimal;

public record OrderLineRequest(String sku, int qty, BigDecimal unitPrice) {
}
