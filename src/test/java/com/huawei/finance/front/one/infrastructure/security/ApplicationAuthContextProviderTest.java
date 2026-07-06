package com.huawei.finance.front.one.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.finance.front.one.domain.auth.UserContext;
import org.junit.jupiter.api.Test;

class ApplicationAuthContextProviderTest {
    @Test
    void resolvesExplicitApplicationIdentity() {
        ApplicationAuthContextProvider provider = provider("tenant_a", "user_a", "User A");

        UserContext user = provider.resolve();

        assertThat(user.tenantId()).isEqualTo("tenant_a");
        assertThat(user.userId()).isEqualTo("user_a");
        assertThat(user.ownerUserId()).isEqualTo("user_a");
        assertThat(user.username()).isEqualTo("User A");
        assertThat(user.userAccount()).isEqualTo("user_a");
        assertThat(user.uuid()).isEqualTo("user_a");
        assertThat(user.userCN()).isEqualTo("User A");
        assertThat(user.displayNameCn()).isEqualTo("User A");
        assertThat(user.displayNameEn()).isEqualTo("User A");
        assertThat(user.userType()).isEqualTo("UNKNOWN");
        assertThat(user.globalUserId()).isNull();
    }

    @Test
    void ownerUserIdPrefersGlobalUserId() {
        UserContext user = new UserContext("tenant_a", "user_a", "User A",
                "account_a", "001", "用户A", "INTERNAL", "uuid_a",
                "user-a", "User A", "用户A", 123456789L);

        assertThat(user.ownerUserId()).isEqualTo("123456789");
    }

    @Test
    void rejectsMissingTenantId() {
        assertThatThrownBy(() -> provider("", "user_a", "User A").resolve())
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("当前租户 ID 缺失");
    }

    @Test
    void rejectsMissingUserId() {
        assertThatThrownBy(() -> provider("tenant_a", "", "User A").resolve())
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("当前用户 ID 缺失");
    }

    @Test
    void rejectsMissingUsername() {
        assertThatThrownBy(() -> provider("tenant_a", "user_a", "").resolve())
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("当前用户名缺失");
    }

    private ApplicationAuthContextProvider provider(String tenantId, String userId, String username) {
        return new ApplicationAuthContextProvider(tenantId, userId, username);
    }
}
