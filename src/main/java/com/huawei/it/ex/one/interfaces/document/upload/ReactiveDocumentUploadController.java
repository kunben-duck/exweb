package com.huawei.it.ex.one.interfaces.document.upload;

import com.huawei.it.ex.one.interfaces.document.dto.DocumentDtoMapper;
import com.huawei.it.ex.one.interfaces.document.dto.UploadedDocumentDto;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Reactive WebFlux 启动模式下的文档上传入口。
 *
 * <p>纯 WebFlux 应用没有 Servlet multipart 解析器，因此上传入口使用 {@link FilePart}。
 * 对外路径、字段名和返回值与 Servlet/MVC 版本保持一致，业务处理统一委托
 * {@link DocumentUploadSupport}。</p>
 */
@RestController
@RequestMapping("/v1/documents")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ReactiveDocumentUploadController {
    private final DocumentUploadSupport uploadSupport;
    private final DocumentDtoMapper dtoMapper;

    public ReactiveDocumentUploadController(DocumentUploadSupport uploadSupport, DocumentDtoMapper dtoMapper) {
        this.uploadSupport = uploadSupport;
        this.dtoMapper = dtoMapper;
    }

    /**
     * 上传本地文件并登记到当前用户文档库。
     *
     * @param file multipart 中名为 {@code file} 的 WebFlux 文件分片。
     * @param sessionId 可选会话标识；传入时服务端会校验会话归属并把文档关联到该会话。
     * @param metadata 上传扩展元数据 JSON，可为空；api-store 模式下可在 metadata.skillId 中放下游技能 ID。
     * @param cookieHeader 原始 HTTP Cookie 头；仅按 provider 配置透传给可信下游 upload。
     * @return 上传完成后的文档库元数据。
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<UploadedDocumentDto> upload(@RequestPart("file") FilePart file,
                                            @RequestPart(value = "sessionId", required = false) String sessionId,
                                            @RequestPart(value = "metadata", required = false) String metadata,
                                            @RequestHeader(value = HttpHeaders.COOKIE, required = false) String cookieHeader) {
        DocumentUploadContext context = new DocumentUploadContext(
                sessionId, metadata, cookieHeader);
        return uploadSupport.uploadFilePart(file, context)
                .map(dtoMapper::toDto);
    }
}
