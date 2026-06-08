package com.huawei.finance.front.one.application.integration.document;

import com.huawei.finance.front.one.application.command.DocumentUploadCommand;
import com.huawei.finance.front.one.application.config.DocumentProviderProperties;
import com.huawei.finance.front.one.domain.auth.UserContext;

/**
 * 文档 provider 上传请求。
 *
 * @param user 请求入口解析出的不可变用户身份。
 * @param documentId ChatService 预生成的统一文档 ID。
 * @param providerCode 目标 provider 编码。
 * @param provider provider 配置。
 * @param command 上传命令，包含文件流和上传上下文。
 */
public record DocumentProviderUploadRequest(
        UserContext user,
        String documentId,
        String providerCode,
        DocumentProviderProperties.ProviderEntry provider,
        DocumentUploadCommand command
) {}
