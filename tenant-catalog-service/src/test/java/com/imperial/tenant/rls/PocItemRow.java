package com.imperial.tenant.rls;

import java.util.UUID;

record PocItemRow(UUID id, UUID tenantId, String value) {
}
