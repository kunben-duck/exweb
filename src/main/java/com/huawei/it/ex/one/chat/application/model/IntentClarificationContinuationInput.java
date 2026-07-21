package com.huawei.it.ex.one.chat.application.model;

import com.huawei.it.ex.one.chat.domain.AttachmentRef;
import com.huawei.it.ex.one.common.event.ChatPayloadMaps;
import com.huawei.it.ex.one.document.application.model.UploadedDocument;
import java.util.List;
import java.util.Map;

/** Trusted input assembled for one intent-clarification continuation. */
public record IntentClarificationContinuationInput(
        String messageText,
        String intentQuery,
        List<AttachmentRef> currentAttachments,
        List<AttachmentRef> cumulativeAttachments,
        List<UploadedDocument> cumulativeDocuments,
        List<String> cumulativeDocumentIds,
        Map<String, Object> runtimeMetadata
) {
    public IntentClarificationContinuationInput {
        messageText = messageText == null ? "" : messageText;
        intentQuery = intentQuery == null ? "" : intentQuery;
        currentAttachments = currentAttachments == null ? List.of() : List.copyOf(currentAttachments);
        cumulativeAttachments = cumulativeAttachments == null ? List.of() : List.copyOf(cumulativeAttachments);
        cumulativeDocuments = cumulativeDocuments == null ? List.of() : List.copyOf(cumulativeDocuments);
        cumulativeDocumentIds = cumulativeDocumentIds == null ? List.of() : List.copyOf(cumulativeDocumentIds);
        runtimeMetadata = runtimeMetadata == null ? Map.of() : ChatPayloadMaps.immutableCopy(runtimeMetadata);
    }
}
