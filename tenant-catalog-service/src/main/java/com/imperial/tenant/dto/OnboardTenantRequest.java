package com.imperial.tenant.dto;

public record OnboardTenantRequest(String name, String username, String password, String email, String phone) {
}
