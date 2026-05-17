package com.huawei.finance.front.one.interfaces.document;

import com.huawei.finance.front.one.application.command.DocumentUpdateCommand;
import com.huawei.finance.front.one.application.command.DocumentUploadCommand;
import com.huawei.finance.front.one.application.facade.DocumentFacade;
import com.huawei.finance.front.one.application.integration.identity.AuthContextProvider;
import com.huawei.finance.front.one.application.service.PermissionChecker;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.document.DocumentDownload;
import com.huawei.finance.front.one.domain.document.DocumentLibraryPage;
import com.huawei.finance.front.one.domain.document.DocumentLibraryQuery;
import com.huawei.finance.front.one.domain.document.StoredObjectContent;
import com.huawei.finance.front.one.domain.document.UploadedDocument;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 文档库接口。
 *
 * <p>本控制器承载文档库的上传、查询、更新、删除、状态查询和受控下载。前端仍然把文件提交到统一后端服务，
 * 接口层先流式写入临时文件，再由应用层通过 ObjectStorage 防腐层上传到真实对象存储。
 * 这样业务入口统一，底层对象存储实现可替换。</p>
 */
@RestController
@RequestMapping("/api/v1/ex/documents")
public class DocumentController {
    private final DocumentFacade facade;
    private final AuthContextProvider auth;
    private final PermissionChecker permissionChecker;

    public DocumentController(DocumentFacade facade, AuthContextProvider auth, PermissionChecker permissionChecker) {
        this.facade = facade;
        this.auth = auth;
        this.permissionChecker = permissionChecker;
    }

    /**
     * 上传本地文件并登记到当前用户文档库。
     *
     * @param file multipart 中名为 file 的文件分片；接口层只做临时落盘，真实存储由 ObjectStorage 防腐层处理。
     * @param sessionId 可选会话标识；传入时服务端会校验会话归属并把文档关联到该会话。
     * @return 上传完成后的文档库元数据。
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<UploadedDocument> upload(@RequestPart("file") FilePart file,
                                         @RequestPart(value = "sessionId", required = false) String sessionId) {
        UserContext user = resolveChatUser();
        return Mono.usingWhen(
                Mono.fromCallable(() -> Files.createTempFile("fin-ex-upload-", ".tmp"))
                        .subscribeOn(Schedulers.boundedElastic()),
                tempFile -> DataBufferUtils.write(file.content(), tempFile,
                                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
                        .then(Mono.defer(() -> uploadTempFile(user, file, sessionId, tempFile))),
                tempFile -> Mono.fromRunnable(() -> deleteQuietly(tempFile)).subscribeOn(Schedulers.boundedElastic())
        );
    }

    /**
     * 查询当前用户文档库。
     *
     * @param sessionId 可选会话标识；传入时只查询该会话关联文档。
     * @param limit 最大返回条数，仓储层会做上限保护。
     * @param cursor 上一页返回的游标；为空时查询第一页。
     * @return 文档库分页结果。
     */
    @GetMapping
    public Mono<DocumentLibraryPage> list(@RequestParam(value = "sessionId", required = false) String sessionId,
                                          @RequestParam(value = "limit", defaultValue = "20") int limit,
                                          @RequestParam(value = "cursor", required = false) String cursor) {
        UserContext user = resolveChatUser();
        return facade.list(user, new DocumentLibraryQuery(sessionId, limit, cursor));
    }

    /**
     * 查询当前用户可见的单个文档。
     *
     * @param documentId 文档标识；服务端会校验文档归属和可用状态。
     * @return 文档元数据。
     */
    @GetMapping("/{documentId}")
    public Mono<UploadedDocument> get(@PathVariable String documentId) {
        UserContext user = resolveChatUser();
        return facade.get(user, documentId);
    }

    /**
     * 更新当前用户文档的展示元数据。
     *
     * @param documentId 文档标识；服务端会校验文档归属和可用状态。
     * @param request 更新请求；字段为空时保留原值。
     * @return 更新后的文档元数据。
     */
    @PatchMapping("/{documentId}")
    public Mono<UploadedDocument> update(@PathVariable String documentId,
                                         @RequestBody(required = false) UpdateDocumentRequest request) {
        UserContext user = resolveChatUser();
        return facade.update(user, documentId, new DocumentUpdateCommand(
                request == null ? null : request.originalName(),
                request == null ? null : request.metadataJson()
        ));
    }

    /**
     * 软删除当前用户文档。
     *
     * @param documentId 文档标识；服务端会校验文档归属和可用状态。
     * @return 删除后的文档元数据快照。
     */
    @DeleteMapping("/{documentId}")
    public Mono<UploadedDocument> delete(@PathVariable String documentId) {
        UserContext user = resolveChatUser();
        return facade.delete(user, documentId);
    }

    /**
     * 查询文档处理状态。
     *
     * @param documentId 文档标识；服务端会校验文档归属。
     * @return 文档状态和 tokenSize。
     */
    @GetMapping("/{documentId}/status")
    public Mono<DocumentStatusDto> status(@PathVariable String documentId) {
        UserContext user = resolveChatUser();
        return facade.get(user, documentId)
                .map(document -> new DocumentStatusDto(document.id(), document.status(), document.tokenSize()));
    }

    /**
     * 获取文档预览访问地址。
     *
     * <p>首版不下发对象存储临时签名，仍走后端受控流式下载，便于统一鉴权和审计。</p>
     *
     * @param documentId 文档标识；服务端会校验文档归属和可用状态。
     * @return 后端受控预览/下载地址。
     */
    @GetMapping("/{documentId}/preview-url")
    public Mono<DocumentAccessDto> previewUrl(@PathVariable String documentId) {
        UserContext user = resolveChatUser();
        return facade.get(user, documentId)
                .map(document -> {
                    if (!document.availableForChat()) {
                        throw new IllegalStateException("文档当前不可预览或下载: " + document.status());
                    }
                    return new DocumentAccessDto(
                            document.id(),
                            "/api/v1/ex/documents/" + document.id() + "/download",
                            "BACKEND_STREAM",
                            null
                    );
                });
    }

    /**
     * 下载当前用户可见的文档对象内容。
     *
     * @param documentId 文档标识；服务端会校验文档归属和可用状态。
     * @return 带 Content-Disposition 的对象内容响应。
     */
    @GetMapping("/{documentId}/download")
    public Mono<ResponseEntity<InputStreamResource>> download(@PathVariable String documentId) {
        UserContext user = resolveChatUser();
        return facade.prepareDownload(user, documentId).map(this::toDownloadResponse);
    }

    private Mono<UploadedDocument> uploadTempFile(UserContext user, FilePart file, String sessionId, Path tempFile) {
        return Mono.fromCallable(() -> {
            long size = Files.size(tempFile);
            InputStream inputStream = Files.newInputStream(tempFile, StandardOpenOption.READ);
            return new DocumentUploadCommand(
                    sessionId,
                    file.filename(),
                    file.headers().getContentType() == null ? null : file.headers().getContentType().toString(),
                    size,
                    inputStream
            );
        }).subscribeOn(Schedulers.boundedElastic()).flatMap(command -> facade.upload(user, command));
    }

    private UserContext resolveChatUser() {
        UserContext user = auth.resolve();
        permissionChecker.checkChatPermission(user);
        return user;
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // 临时文件清理失败不影响上传结果，容器或宿主机的临时目录清理策略会兜底处理。
        }
    }

    private ResponseEntity<InputStreamResource> toDownloadResponse(DocumentDownload download) {
        UploadedDocument document = download.document();
        StoredObjectContent content = download.content();
        try {
            ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                    .contentType(safeMediaType(document.contentType()))
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename(document.originalName())
                            .build()
                            .toString());
            if (content.sizeBytes() >= 0) {
                builder.contentLength(content.sizeBytes());
            }
            return builder.body(new InputStreamResource(content.inputStream()));
        } catch (RuntimeException ex) {
            closeQuietly(content.inputStream());
            throw ex;
        }
    }

    private MediaType safeMediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (InvalidMediaTypeException ex) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private void closeQuietly(InputStream inputStream) {
        if (inputStream == null) {
            return;
        }
        try {
            inputStream.close();
        } catch (Exception ignored) {
            // 响应组装失败时尽力关闭对象存储流，原始异常由调用栈继续抛出。
        }
    }
}
