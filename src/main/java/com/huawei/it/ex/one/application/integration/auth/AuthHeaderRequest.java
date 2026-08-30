/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.auth;

/**
 * 集成服务鉴权请求头构造上下文。
 *
 * @param tenantId 租户标识；为空时 provider 只能按服务级配置取 token。
 * @param userId 用户标识。
 * @param serviceCode 目标服务编码，例如 welink-share、intent-service。
 * @param operationCode 目标操作编码，例如 send、recognize、query。
 * @param baseUrl 目标服务基础地址或完整 endpoint。
 * @param path 目标接口路径。
 * @param providerCode 目标业务 provider 编码，例如 welink 或具体 DomainAgent 编码。
 */
public record AuthHeaderRequest(
        String tenantId,
        String userId,
        String serviceCode,
        String operationCode,
        String baseUrl,
        String path,
        String providerCode
) {
}
