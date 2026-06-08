package com.huawei.finance.front.one.interfaces.document;

import com.huawei.finance.front.one.domain.document.UploadedDocument;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;

/**
 * Servlet/MVC 启动模式下的文档上传入口。
 *
 * <p>企业框架引入 {@code spring-boot-starter-web} 后，Spring Boot 会默认选择 Servlet 应用类型。
 * MVC 不能把 multipart 文件绑定为 WebFlux {@code FilePart}，因此这里用 {@link MultipartFile}
 * 暴露与前端完全相同的 {@code POST /api/v1/ex/documents} 契约。</p>
 */
@RestController
@RequestMapping("/api/v1/ex/documents")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class MvcDocumentUploadController {
    private final DocumentUploadSupport uploadSupport;

    public MvcDocumentUploadController(DocumentUploadSupport uploadSupport) {
        this.uploadSupport = uploadSupport;
    }

    /**
     * 上传本地文件并登记到当前用户文档库。
     *
     * @param file multipart 中名为 {@code file} 的文件。
     * @param sessionId 可选会话标识；传入时服务端会校验会话归属并把文档关联到该会话。
     * @param targetProvider 目标文档 provider；为空时使用默认对象存储。
     * @param skillId 上传关联技能标识，可为空。
     * @param metadata 上传扩展元数据 JSON，可为空。
     * @return 上传完成后的文档库元数据。
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<UploadedDocument> upload(@RequestPart("file") MultipartFile file,
                                         @RequestParam(value = "sessionId", required = false) String sessionId,
                                         @RequestParam(value = "targetProvider", required = false) String targetProvider,
                                         @RequestParam(value = "skillId", required = false) String skillId,
                                         @RequestParam(value = "metadata", required = false) String metadata) {
        return uploadSupport.uploadMultipartFile(file, sessionId, targetProvider, skillId, metadata);
    }
}
