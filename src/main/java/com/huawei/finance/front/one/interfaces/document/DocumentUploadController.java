package com.huawei.finance.front.one.interfaces.document;

import com.huawei.finance.front.one.application.command.DocumentUploadCommand;
import com.huawei.finance.front.one.application.facade.DocumentUploadFacade;
import com.huawei.finance.front.one.domain.document.UploadedDocument;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 文档上传接口。
 *
 * <p>第一版先把 FilePart 聚合成输入流后交给对象存储 port；后续大文件场景应改为流式落盘或对象存储直传。</p>
 */
@RestController
@RequestMapping("/api/v1/finance/documents")
public class DocumentUploadController {
    private final DocumentUploadFacade facade;
    public DocumentUploadController(DocumentUploadFacade facade) { this.facade = facade; }
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<UploadedDocument> upload(@RequestPart("file") FilePart file,
                                         @RequestPart(value = "sessionId", required = false) String sessionId,
                                         @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
                                         @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId) {
        // 当前实现会把整个上传文件读入内存，适合小文件和第一版验证。
        return file.content().reduce(new java.io.ByteArrayOutputStream(), (out, dataBuffer) -> {
            try {
                byte[] bytes = new byte[dataBuffer.readableByteCount()];
                dataBuffer.read(bytes);
                out.write(bytes);
                org.springframework.core.io.buffer.DataBufferUtils.release(dataBuffer);
                return out;
            } catch (Exception e) { throw new RuntimeException(e); }
        }).flatMap(out -> facade.upload(new DocumentUploadCommand(tenantId, userId, sessionId, file.filename(), file.headers().getContentType() == null ? null : file.headers().getContentType().toString(), out.size(), new java.io.ByteArrayInputStream(out.toByteArray()))));
    }
}
