package com.huawei.finance.front.one.interfaces.document.upload;

/**
 * 文档上传入口的请求上下文。
 *
 * <p>该对象只收敛接口层方法签名；multipart 字段名、HTTP 请求结构和应用层
 * {@code DocumentUploadCommand} 均保持不变。</p>
 *
 * @param sessionId 可选会话标识。
 * @param targetProvider 目标文档 provider；为空使用默认 provider。
 * @param skillId 上传关联的显式技能 ID。
 * @param metadataJson 前端上传扩展元数据 JSON。
 * @param cookieHeader 原始 Cookie 头，只作为内存快照传给允许透传的 provider，不持久化。
 */
public record DocumentUploadContext(
        String sessionId,
        String targetProvider,
        String skillId,
        String metadataJson,
        String cookieHeader
) {
}
