package com.imperial.order.dto;

import java.util.List;

/**
 * No tenantId field here on purpose: the tenant placing the order is always
 * TenantContext.get() (from the caller's own JWT), never something the
 * client gets to assert in the request body.
 */
public record PlaceOrderRequest(List<OrderLineRequest> items) {
}
