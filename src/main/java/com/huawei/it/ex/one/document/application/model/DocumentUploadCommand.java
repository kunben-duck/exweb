package com.huawei.it.ex.one.document.application.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.huawei.it.ex.one.common.http.RuntimeForwardHeaders;
import java.io.InputStream;

/**
 * 文档上传应用命令。
 *
 * @param sessionId 上传文档关联的聊天会话标识，可为空表示尚未绑定会话。
 * @param originalFilename 用户上传时的原始文件名。
 * @param contentType 文件 MIME 类型，由接口层或浏览器上传协议提供。
 * @param sizeBytes 文件字节大小。
 * @param inputStream 文件内容输入流，由应用服务负责读取并关闭。
 * @param metadataJson 上传扩展元数据 JSON，可为空；只用于审计和 provider adapter 参数。
 * @param forwardHeaders 请求入口捕获的 Cookie 等转发头快照；仅用于出站存储请求头，不能进入 form 或 metadata。
 */
public record DocumentUploadCommand(
        String sessionId,
        String originalFilename,
        String contentType,
        long sizeBytes,
        InputStream inputStream,
        String metadataJson,
        @JsonIgnore RuntimeForwardHeaders forwardHeaders
) {
    public DocumentUploadCommand {
        forwardHeaders = forwardHeaders == null ? RuntimeForwardHeaders.empty() : forwardHeaders;
    }

    /**
     * 兼容现有默认对象存储上传的构造器。
     */
    public DocumentUploadCommand(String sessionId, String originalFilename, String contentType, long sizeBytes,
                                 InputStream inputStream) {
        this(sessionId, originalFilename, contentType, sizeBytes, inputStream, null,
                RuntimeForwardHeaders.empty());
    }

    /**
     * 兼容不需要 Cookie 透传的上传调用。
     */
    public DocumentUploadCommand(String sessionId, String originalFilename, String contentType, long sizeBytes,
                                 InputStream inputStream, String metadataJson) {
        this(sessionId, originalFilename, contentType, sizeBytes, inputStream, metadataJson,
                RuntimeForwardHeaders.empty());
    }
}
