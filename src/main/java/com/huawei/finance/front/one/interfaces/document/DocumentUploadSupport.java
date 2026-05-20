package com.huawei.finance.front.one.interfaces.document;

import com.huawei.finance.front.one.application.command.DocumentUploadCommand;
import com.huawei.finance.front.one.application.config.DocumentUploadProperties;
import com.huawei.finance.front.one.application.facade.DocumentFacade;
import com.huawei.finance.front.one.application.integration.identity.AuthContextProvider;
import com.huawei.finance.front.one.application.service.PermissionChecker;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.document.UploadedDocument;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 文档上传接口层共享组件。
 *
 * <p>上传协议在不同 Spring 启动模式下不同：Servlet/MVC 使用 {@link MultipartFile}，
 * Reactive WebFlux 使用 {@link FilePart}。本组件把两种入口统一收敛为临时文件，再交给
 * {@link DocumentFacade} 通过 ObjectStorage 防腐层写入真实对象存储，避免 Controller 分叉出两套业务逻辑。</p>
 *
 * <p>用户身份只在请求入口解析一次，并在切换到 {@code boundedElastic} 执行文件和数据库阻塞操作前
 * 固化为不可变 {@link UserContext}，便于后续企业 ThreadLocal 权限框架接入。</p>
 */
@Component
public class DocumentUploadSupport {
    private final DocumentFacade facade;
    private final AuthContextProvider auth;
    private final PermissionChecker permissionChecker;
    private final DocumentUploadProperties uploadProperties;

    public DocumentUploadSupport(DocumentFacade facade, AuthContextProvider auth, PermissionChecker permissionChecker,
                                 DocumentUploadProperties uploadProperties) {
        this.facade = facade;
        this.auth = auth;
        this.permissionChecker = permissionChecker;
        this.uploadProperties = uploadProperties;
    }

    /**
     * 处理 Servlet/MVC multipart 上传。
     *
     * @param file MVC multipart 文件对象，字段名固定为 {@code file}。
     * @param sessionId 可选会话标识；传入时应用层会校验会话归属并建立文档关联。
     * @return 上传完成后的文档库元数据。
     */
    public Mono<UploadedDocument> uploadMultipartFile(MultipartFile file, String sessionId) {
        UserContext user = resolveChatUser();
        return Mono.usingWhen(
                createTempFile(),
                tempFile -> copyMultipartFile(file, tempFile)
                        .then(Mono.defer(() -> uploadTempFile(
                                user,
                                sessionId,
                                file == null ? null : file.getOriginalFilename(),
                                file == null ? null : file.getContentType(),
                                tempFile
                        ))),
                this::deleteTempFile
        );
    }

    /**
     * 处理 Reactive WebFlux multipart 上传。
     *
     * @param file WebFlux 文件分片，字段名固定为 {@code file}。
     * @param sessionId 可选会话标识；传入时应用层会校验会话归属并建立文档关联。
     * @return 上传完成后的文档库元数据。
     */
    public Mono<UploadedDocument> uploadFilePart(FilePart file, String sessionId) {
        UserContext user = resolveChatUser();
        return Mono.usingWhen(
                createTempFile(),
                tempFile -> writeFilePart(file, tempFile)
                        .then(Mono.defer(() -> uploadTempFile(
                                user,
                                sessionId,
                                file == null ? null : file.filename(),
                                file == null ? null : mediaType(file.headers().getContentType()),
                                tempFile
                        ))),
                this::deleteTempFile
        );
    }

    private Mono<Path> createTempFile() {
        return Mono.fromCallable(() -> Files.createTempFile("fin-ex-upload-", ".tmp"))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Void> copyMultipartFile(MultipartFile file, Path tempFile) {
        return Mono.fromRunnable(() -> {
            if (file == null) {
                throw new IllegalArgumentException("上传文件不能为空");
            }
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ex) {
                throw new IllegalStateException("读取上传文件失败", ex);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private Mono<Void> writeFilePart(FilePart file, Path tempFile) {
        if (file == null) {
            return Mono.error(new IllegalArgumentException("上传文件不能为空"));
        }
        return DataBufferUtils.write(file.content(), tempFile,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private Mono<UploadedDocument> uploadTempFile(UserContext user,
                                                 String sessionId,
                                                 String originalFilename,
                                                 String contentType,
                                                 Path tempFile) {
        return Mono.fromCallable(() -> {
            long size = Files.size(tempFile);
            if (size > uploadProperties.normalizedMaxUploadSizeBytes()) {
                throw new IllegalArgumentException("上传文件超过最大允许大小: " + uploadProperties.normalizedMaxUploadSizeBytes());
            }
            InputStream inputStream = Files.newInputStream(tempFile, StandardOpenOption.READ);
            return new DocumentUploadCommand(sessionId, originalFilename, contentType, size, inputStream);
        }).subscribeOn(Schedulers.boundedElastic()).flatMap(command -> facade.upload(user, command));
    }

    private Mono<Void> deleteTempFile(Path path) {
        return Mono.fromRunnable(() -> {
            try {
                Files.deleteIfExists(path);
            } catch (Exception ignored) {
                // 临时文件清理失败不影响上传结果，容器或宿主机的临时目录清理策略会兜底处理。
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private UserContext resolveChatUser() {
        UserContext user = auth.resolve();
        permissionChecker.checkChatPermission(user);
        return user;
    }

    private String mediaType(MediaType contentType) {
        return contentType == null ? null : contentType.toString();
    }
}
