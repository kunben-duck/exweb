package com.huawei.it.ex.one.share.interfaces.http;

import com.huawei.it.ex.one.share.domain.ChatShare;
import com.huawei.it.ex.one.share.domain.ChatShareAttachmentSnapshot;
import com.huawei.it.ex.one.share.domain.ChatShareDelivery;
import com.huawei.it.ex.one.share.domain.ChatShareMessageSnapshot;
import com.huawei.it.ex.one.share.domain.ChatSharePage;
import com.huawei.it.ex.one.share.domain.ChatShareSnapshotPart;
import com.huawei.it.ex.one.share.interfaces.dto.ChatShareAttachmentSnapshotDto;
import com.huawei.it.ex.one.share.interfaces.dto.ChatShareDetailDto;
import com.huawei.it.ex.one.share.interfaces.dto.ChatShareDeliveryDto;
import com.huawei.it.ex.one.share.interfaces.dto.ChatShareDto;
import com.huawei.it.ex.one.share.interfaces.dto.ChatSharePageDto;
import com.huawei.it.ex.one.share.interfaces.dto.ChatSharePartDto;
import com.huawei.it.ex.one.share.interfaces.dto.ChatShareSnapshotMessageDto;
import java.util.List;
import org.springframework.stereotype.Component;

/** Maps immutable share domain snapshots to the existing HTTP response contract. */
@Component
public final class ChatShareViewAssembler {
    public ChatSharePageDto toPageDto(ChatSharePage page) {
        return new ChatSharePageDto(
                page.items().stream().map(this::toDto).toList(),
                page.curPage(),
                page.pageSize(),
                page.totalRows(),
                page.totalPages()
        );
    }

    public ChatShareDetailDto toDetailDto(ChatShare share) {
        return new ChatShareDetailDto(
                toDto(share),
                toMessageDto(share.snapshot().question()),
                toMessageDto(share.snapshot().answer()),
                toPartDtos(share.snapshot().parts())
        );
    }

    public ChatShareDto toDto(ChatShare share) {
        return new ChatShareDto(
                share.id(),
                share.title(),
                share.scope(),
                share.visibility(),
                share.status(),
                share.expiresAt(),
                share.sourceSessionId(),
                share.sourceUserMessageId(),
                share.sourceAssistantMessageId(),
                share.sourceRunId(),
                share.createdAt(),
                share.updatedAt()
        );
    }

    public ChatShareDeliveryDto toDeliveryDto(ChatShareDelivery delivery) {
        return new ChatShareDeliveryDto(
                delivery.id(),
                delivery.shareId(),
                delivery.provider(),
                delivery.status(),
                delivery.linkUrl(),
                delivery.errorCode(),
                delivery.errorMessage(),
                delivery.sentAt(),
                delivery.createdAt(),
                delivery.updatedAt()
        );
    }

    private ChatShareSnapshotMessageDto toMessageDto(ChatShareMessageSnapshot message) {
        return new ChatShareSnapshotMessageDto(
                message.messageId(),
                message.sessionId(),
                message.role(),
                message.content(),
                message.runId(),
                message.metadataJson(),
                toAttachmentDtos(message.attachments()),
                message.createdAt()
        );
    }

    private List<ChatShareAttachmentSnapshotDto> toAttachmentDtos(
            List<ChatShareAttachmentSnapshot> attachments) {
        return attachments == null ? List.of() : attachments.stream()
                .map(attachment -> new ChatShareAttachmentSnapshotDto(
                        attachment.documentId(),
                        attachment.name(),
                        attachment.contentType(),
                        attachment.sizeBytes()
                ))
                .toList();
    }

    private List<ChatSharePartDto> toPartDtos(List<ChatShareSnapshotPart> parts) {
        return parts == null ? List.of() : parts.stream()
                .map(part -> new ChatSharePartDto(
                        part.partId(),
                        part.messageId(),
                        part.runId(),
                        part.partType(),
                        part.sourceType(),
                        part.contentText(),
                        part.title(),
                        part.status(),
                        part.channel(),
                        part.displayHint(),
                        part.visible(),
                        part.payload(),
                        part.partOrder(),
                        part.createdAt()
                ))
                .toList();
    }
}
