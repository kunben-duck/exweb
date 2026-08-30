/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.domain.auth.UserContext;

import org.junit.jupiter.api.Test;

class ApplicationAuthContextProviderTest {
    @Test
    void resolvesPlaceholderApplicationIdentityWithAllUserContextFields() {
        ApplicationAuthContextProvider provider = new ApplicationAuthContextProvider();

        UserContext user = provider.resolve();

        assertThat(user.tenantId()).isEqualTo("1111");
        assertThat(user.userId()).isEqualTo("1111");
        assertThat(user.username()).isEqualTo("默认用户");
        assertThat(user.userAccount()).isEqualTo("1111");
        assertThat(user.employeeNumber()).isEqualTo("1111");
        assertThat(user.userCN()).isEqualTo("默认用户");
        assertThat(user.userType()).isEqualTo("INTERNAL");
        assertThat(user.uuid()).isEqualTo("1111");
        assertThat(user.employeeNameEng()).isEqualTo("default-user");
        assertThat(user.displayNameEn()).isEqualTo("Default User");
        assertThat(user.displayNameCn()).isEqualTo("默认用户");
        assertThat(user.globalUserId()).isEqualTo(1111L);
        assertThat(user.ownerUserId()).isEqualTo("1111");
    }

    @Test
    void ownerUserIdPrefersGlobalUserId() {
        UserContext user = new UserContext("tenant_a", "user_a", "User A",
                "account_a", "001", "用户A", "INTERNAL", "uuid_a",
                "user-a", "User A", "用户A", 123456789L);

        assertThat(user.ownerUserId()).isEqualTo("123456789");
    }
}
