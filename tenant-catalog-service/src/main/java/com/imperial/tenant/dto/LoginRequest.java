package com.imperial.tenant.dto;


/**
 * Phase 1 simplification: no password/user-credential model exists yet, so
 * "login" is just "prove you know a tenant id that was already onboarded."
 * A real credential check is out of scope until a Users/dealers table shows
 * up in a later phase - this endpoint's only job right now is to exercise
 * the JWT issuance -> validation path end to end.
 */
public record LoginRequest(String username, String password) {
}
