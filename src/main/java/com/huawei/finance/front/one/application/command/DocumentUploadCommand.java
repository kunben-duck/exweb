package com.huawei.finance.front.one.application.command;

import java.io.InputStream;

/**
 * 文档上传应用命令。
 *
 * @param sessionId 上传文档关联的聊天会话标识，可为空表示尚未绑定会话。
 * @param originalFilename 用户上传时的原始文件名。
 * @param contentType 文件 MIME 类型，由接口层或浏览器上传协议提供。
 * @param sizeBytes 文件字节大小。
 * @param inputStream 文件内容输入流，由应用服务负责读取并关闭。
 */
public record DocumentUploadCommand(
        String sessionId,
        String originalFilename,
        String contentType,
        long sizeBytes,
        InputStream inputStream
) {}
