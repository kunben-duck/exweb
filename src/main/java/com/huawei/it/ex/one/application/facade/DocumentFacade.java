/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.facade;

import com.huawei.it.ex.one.application.command.DocumentUpdateCommand;
import com.huawei.it.ex.one.application.command.DocumentUploadCommand;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.AttachmentRef;
import com.huawei.it.ex.one.domain.document.DocumentDownload;
import com.huawei.it.ex.one.domain.document.DocumentLibraryPage;
import com.huawei.it.ex.one.domain.document.DocumentLibraryQuery;
import com.huawei.it.ex.one.domain.document.StoredObjectContent;
import com.huawei.it.ex.one.domain.document.UploadedDocument;

import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 文档库应用门面。
 *
 * <p>接口层只依赖该门面完成本地上传、文档库查询和聊天附件解析。用户身份必须由请求入口
 * 解析为不可变 {@link UserContext} 后显式传入，避免文档阻塞操作切换线程后再读取
 * ThreadLocal 权限上下文。</p>
 */
public interface DocumentFacade {
    /**
     * 上传本地文件并登记为文档库资产。
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param command 上传命令。
     * @return 上传后的文档元数据。
     */
    Mono<UploadedDocument> upload(UserContext user, DocumentUploadCommand command);

    /**
     * 查询当前用户可见的文档库。
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param query 查询条件。
     * @return 文档分页结果。
     */
    Mono<DocumentLibraryPage> list(UserContext user, DocumentLibraryQuery query);

    /**
     * 查询当前用户可见的单个文档。
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param documentId 文档标识。
     * @return 文档元数据。
     */
    Mono<UploadedDocument> get(UserContext user, String documentId);

    /**
     * 更新当前用户可见文档的展示元数据。
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param documentId 文档标识。
     * @param command 更新命令。
     * @return 更新后的文档元数据。
     */
    Mono<UploadedDocument> update(UserContext user, String documentId, DocumentUpdateCommand command);

    /**
     * 软删除当前用户可见文档。
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param documentId 文档标识。
     * @return 删除后的文档元数据快照。
     */
    Mono<UploadedDocument> delete(UserContext user, String documentId);

    /**
     * 读取当前用户可下载文档的元数据和对象内容。
     *
     * <p>该方法在 application 层一次性完成身份、归属、状态和对象读取，接口层不再分别调用
     * {@link #get(UserContext, String)} 与 {@link #download(UserContext, String)}，
     * 避免重复校验和资源释放不一致。</p>
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param documentId 文档标识。
     * @return 文档元数据与对象内容流。
     */
    Mono<DocumentDownload> prepareDownload(UserContext user, String documentId);

    /**
     * 校验当前文档是否支持通过本服务生成预览/下载访问入口。
     *
     * <p>不同 provider 的文件内容可能托管在下游系统。该方法只做归属、状态和 provider 下载能力校验，
     * 不提前打开文件流，避免 preview-url 接口造成不必要的对象存储或下游下载开销。</p>
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param documentId 文档标识。
     * @return 可通过本服务访问内容的文档元数据。
     */
    Mono<UploadedDocument> prepareAccess(UserContext user, String documentId);

    /**
     * 读取当前用户可见文档的对象内容。
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param documentId 文档标识。
     * @return 对象内容流，调用方负责关闭 inputStream。
     */
    Mono<StoredObjectContent> download(UserContext user, String documentId);

    /**
     * 使用已经解析好的身份快照解析附件。
     *
     * <p>后台 run 会脱离原始 HTTP 请求线程执行，因此不能在异步线程里再次读取权限上下文。
     * 该方法让聊天编排把入口解析出的 {@link UserContext} 直接传入文档防腐层。</p>
     *
     * @param user 当前调用方身份快照。
     * @param attachments 前端传入的附件引用。
     * @return 使用数据库元数据补齐后的附件引用。
     */
    List<AttachmentRef> resolveAttachmentsForUser(UserContext user, List<AttachmentRef> attachments);

    /**
     * 解析当前用户可用的完整文档元数据。
     *
     * <p>DomainAgent adapter 需要读取 provider 私有文档 ID 和 metadataJson 来组装下游 chat 入参，
     * 因此不能只依赖 {@link AttachmentRef} 的展示字段。</p>
     *
     * @param user 当前调用方身份快照。
     * @param attachments 前端传入的附件引用。
     * @return 已完成归属和状态校验的文档元数据。
     */
    List<UploadedDocument> resolveDocumentsForUser(UserContext user, List<AttachmentRef> attachments);

    /**
     * 通过一次文档事实解析同时生成可信消息附件和 Runtime 文档元数据。
     *
     * <p>实现必须忽略前端提交的名称、MIME、大小和来源，并保证同一批次中重复的
     * documentId 不会触发重复事实查询。</p>
     */
    ResolvedChatAttachments resolveChatAttachmentsForUser(UserContext user, List<AttachmentRef> attachments);

    /**
     * 深复制本轮业务 metadata，并使用可信文档覆盖 {@code sceneParam.docList}。
     */
    Map<String, Object> replaceRuntimeDocumentMetadata(Map<String, Object> metadata,
                                                       List<UploadedDocument> documents);
}
