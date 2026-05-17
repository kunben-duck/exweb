package com.huawei.finance.front.one.application.facade;

import com.huawei.finance.front.one.application.command.DocumentUpdateCommand;
import com.huawei.finance.front.one.application.command.DocumentUploadCommand;
import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.document.DocumentDownload;
import com.huawei.finance.front.one.domain.document.DocumentLibraryPage;
import com.huawei.finance.front.one.domain.document.DocumentLibraryQuery;
import com.huawei.finance.front.one.domain.document.StoredObjectContent;
import com.huawei.finance.front.one.domain.document.UploadedDocument;
import java.util.List;
import reactor.core.publisher.Mono;

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
     * {@link #get(String)} 与 {@link #download(String)}，避免重复校验和资源释放不一致。</p>
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param documentId 文档标识。
     * @return 文档元数据与对象内容流。
     */
    Mono<DocumentDownload> prepareDownload(UserContext user, String documentId);

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
}
