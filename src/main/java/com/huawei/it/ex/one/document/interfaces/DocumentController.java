package com.huawei.it.ex.one.document.interfaces;

import com.huawei.it.ex.one.document.application.model.DocumentUpdateCommand;
import com.huawei.it.ex.one.document.application.service.DocumentService;
import com.huawei.it.ex.one.security.application.context.AuthContextProvider;
import com.huawei.it.ex.one.security.application.service.PermissionChecker;
import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.document.domain.DocumentDownload;
import com.huawei.it.ex.one.document.domain.DocumentLibraryQuery;
import com.huawei.it.ex.one.document.domain.StoredObjectContent;
import com.huawei.it.ex.one.document.application.model.UploadedDocument;
import com.huawei.it.ex.one.document.interfaces.dto.DocumentAccessDto;
import com.huawei.it.ex.one.document.interfaces.dto.DocumentDtoMapper;
import com.huawei.it.ex.one.document.interfaces.dto.DocumentLibraryPageDto;
import com.huawei.it.ex.one.document.interfaces.dto.DocumentStatusDto;
import com.huawei.it.ex.one.document.interfaces.dto.UpdateDocumentRequest;
import com.huawei.it.ex.one.document.interfaces.dto.UploadedDocumentDto;
import com.huawei.it.ex.one.document.interfaces.upload.DocumentUploadSupport;
import java.io.InputStream;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 文档库接口。
 *
 * <p>本控制器承载文档库的查询、更新、删除、状态查询和受控下载。上传入口需要同时适配
 * Servlet/MVC 的 {@code MultipartFile} 与 Reactive WebFlux 的 {@code FilePart}，因此拆分到
 * 启动模式专用 Controller，并统一复用 {@link DocumentUploadSupport} 完成临时文件和对象存储写入。</p>
 */
@RestController
@RequestMapping("/v1/documents")
public class DocumentController {
    private final DocumentService facade;
    private final AuthContextProvider auth;
    private final PermissionChecker permissionChecker;
    private final DocumentDtoMapper dtoMapper;

    public DocumentController(DocumentService facade, AuthContextProvider auth, PermissionChecker permissionChecker,
                              DocumentDtoMapper dtoMapper) {
        this.facade = facade;
        this.auth = auth;
        this.permissionChecker = permissionChecker;
        this.dtoMapper = dtoMapper;
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
    public Mono<DocumentLibraryPageDto> list(@RequestParam(value = "sessionId", required = false) String sessionId,
                                             @RequestParam(value = "limit", defaultValue = "20") int limit,
                                             @RequestParam(value = "cursor", required = false) String cursor) {
        UserContext user = resolveChatUser();
        return facade.list(user, new DocumentLibraryQuery(sessionId, limit, cursor))
                .map(dtoMapper::toDto);
    }

    /**
     * 查询当前用户可见的单个文档。
     *
     * @param documentId 文档标识；服务端会校验文档归属和可用状态。
     * @return 文档元数据。
     */
    @GetMapping("/{documentId}")
    public Mono<UploadedDocumentDto> get(@PathVariable("documentId") String documentId) {
        UserContext user = resolveChatUser();
        return facade.get(user, documentId)
                .map(dtoMapper::toDto);
    }

    /**
     * 更新当前用户文档的展示元数据。
     *
     * @param documentId 文档标识；服务端会校验文档归属和可用状态。
     * @param request 更新请求；字段为空时保留原值。
     * @return 更新后的文档元数据。
     */
    @PatchMapping("/{documentId}")
    public Mono<UploadedDocumentDto> update(@PathVariable("documentId") String documentId,
                                            @RequestBody(required = false) UpdateDocumentRequest request) {
        UserContext user = resolveChatUser();
        return facade.update(user, documentId, new DocumentUpdateCommand(
                request == null ? null : request.originalName(),
                request == null ? null : request.metadataJson()
        )).map(dtoMapper::toDto);
    }

    /**
     * 软删除当前用户文档。
     *
     * @param documentId 文档标识；服务端会校验文档归属和可用状态。
     * @return 删除后的文档元数据快照。
     */
    @DeleteMapping("/{documentId}")
    public Mono<UploadedDocumentDto> delete(@PathVariable("documentId") String documentId) {
        UserContext user = resolveChatUser();
        return facade.delete(user, documentId)
                .map(dtoMapper::toDto);
    }

    /**
     * 查询文档处理状态。
     *
     * @param documentId 文档标识；服务端会校验文档归属。
     * @return 文档状态和 tokenSize。
     */
    @GetMapping("/{documentId}/status")
    public Mono<DocumentStatusDto> status(@PathVariable("documentId") String documentId) {
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
    public Mono<DocumentAccessDto> previewUrl(@PathVariable("documentId") String documentId) {
        UserContext user = resolveChatUser();
        return facade.prepareAccess(user, documentId)
                .map(document -> {
                    return new DocumentAccessDto(
                            document.id(),
                            "/v1/documents/" + document.id() + "/download",
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
    public Mono<ResponseEntity<InputStreamResource>> download(@PathVariable("documentId") String documentId) {
        UserContext user = resolveChatUser();
        return facade.prepareDownload(user, documentId).map(this::toDownloadResponse);
    }

    private UserContext resolveChatUser() {
        UserContext user = auth.resolve();
        permissionChecker.checkChatPermission(user);
        return user;
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
