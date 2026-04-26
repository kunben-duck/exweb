package com.huawei.finance.front.one.application.gateway;

import com.huawei.finance.front.one.domain.auth.UserContext;

public interface AuthContextProvider { UserContext resolve(String tenantId, String userId); }
