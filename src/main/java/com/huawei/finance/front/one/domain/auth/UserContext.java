package com.huawei.finance.front.one.domain.auth;

import java.util.Set;

public record UserContext(
        String tenantId,
        String userId,
        String username,
        Set<String> scopes
) {
    public boolean hasScope(String scope) {
        return scopes != null && (scopes.contains(scope) || scopes.contains("*") );
    }
}
