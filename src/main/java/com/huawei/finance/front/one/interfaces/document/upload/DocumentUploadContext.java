package com.huawei.finance.front.one.interfaces.document.upload;

/**
 * 文档上传入口的请求上下文。
 *
 * <p>该对象只收敛接口层方法签名；Controller 负责把 multipart 字段绑定成
 * {@code domainAgentId} 等业务语义，再交给应用层命令处理。</p>
 *
 * @param sessionId 可选会话标识。
 * @param targetProvider 目标文档 provider；为空使用默认 provider。
 * @param domainAgentId 上传关联的 DomainAgent ID。
 * @param metadataJson 前端上传扩展元数据 JSON。
 * @param cookieHeader 原始 Cookie 头，只作为内存快照传给允许透传的 provider，不持久化。
 */
public record DocumentUploadContext(
        String sessionId,
        String targetProvider,
        String domainAgentId,
        String metadataJson,
        String cookieHeader
) {
}
