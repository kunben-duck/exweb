/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.auth;

import com.huawei.it.ex.one.application.integration.auth.AuthHeaderProvider;
import com.huawei.it.ex.one.application.integration.auth.AuthHeaderRequest;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 不添加任何集成服务鉴权头的 provider。
 */
@Component
public class NoopAuthHeaderProvider implements AuthHeaderProvider {
    @Override
    public String providerCode() {
        return "none";
    }

    @Override
    public Map<String, String> headers(AuthHeaderRequest request) {
        return Map.of();
    }
}
