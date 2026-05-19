package com.huawei.finance.front.one.interfaces.document;

import com.huawei.finance.front.one.domain.document.UploadedDocument;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.PostMapping;
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
@RequestMapping("/api/v1/ex/documents")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ReactiveDocumentUploadController {
    private final DocumentUploadSupport uploadSupport;

    public ReactiveDocumentUploadController(DocumentUploadSupport uploadSupport) {
        this.uploadSupport = uploadSupport;
    }

    /**
     * 上传本地文件并登记到当前用户文档库。
     *
     * @param file multipart 中名为 {@code file} 的 WebFlux 文件分片。
     * @param sessionId 可选会话标识；传入时服务端会校验会话归属并把文档关联到该会话。
     * @return 上传完成后的文档库元数据。
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<UploadedDocument> upload(@RequestPart("file") FilePart file,
                                         @RequestPart(value = "sessionId", required = false) String sessionId) {
        return uploadSupport.uploadFilePart(file, sessionId);
    }
}
