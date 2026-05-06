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
        assertThat(user.username()).isEqualTo("User A");
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
        return new ApplicationAuthContextProvider(new ConfiguredUserIdResolver(), tenantId, userId, username);
    }
}
