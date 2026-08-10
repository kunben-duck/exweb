package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceMetadata;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceState;
import com.huawei.it.ex.one.application.service.runtime.RuntimeStreamLimitExceededException;
import com.huawei.it.ex.one.application.service.runtime.RuntimeStreamLimitType;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatMessagePartDraft;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单次 run 内的 assistant 汇总状态。
 *
 * <p>流式 delta 负责实时草稿；下游最终 {@code message.snapshot} 是更权威的最终正文。
 * {@code runtime.*} 事件只保存为历史过程 parts，不混入 assistant 正文。若没有正文但存在卡片、
 * 引用、思考或进度等用户可见 part，也会创建一条空正文 assistant 消息作为 parts 挂载点。</p>
 */
final class AssistantAssembly implements AutoCloseable {
    private static final AppLogger log = AppLoggerFactory.getLogger(AssistantAssembly.class);

    private StringBuilder deltaDraft = new StringBuilder();
    private final List<PartEntry> parts = new ArrayList<>();
    private final AgentDataPersistenceState persistenceState;
    private final AssistantAssemblyBudgetRegistry budgetRegistry;
    private final AssistantAssemblyBudgetRegistry.Budget budget;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private String snapshot;
    private String structuredFallbackContent;
    private boolean persistableOutputObserved;
    private boolean nonControlOutputObserved;
    private long bodyBytes;
    private long structuredFallbackBytes;
    private long filteredPartCount;
    private long filteredPartBytes;
    private long evictedPartCount;
    private long evictedPartBytes;
    private boolean filterWarningLogged;
    private boolean stopSealed;

    AssistantAssembly() {
        this(AgentDataPersistenceState.full());
    }

    AssistantAssembly(AgentDataPersistenceState persistenceState) {
        this(persistenceState, null, null);
    }

    AssistantAssembly(AgentDataPersistenceState persistenceState,
                      AssistantAssemblyBudgetRegistry budgetRegistry,
                      AssistantAssemblyBudgetRegistry.Budget budget) {
        this.persistenceState = persistenceState == null
                ? AgentDataPersistenceState.full()
                : persistenceState;
        this.budgetRegistry = budgetRegistry;
        this.budget = budget;
    }

    synchronized ObservationResult observe(ChatEvent event) {
        if (stopSealed || closed.get()) {
            return ObservationResult.accepted();
        }
        if (event == null || event.payload() == null) {
            return ObservationResult.accepted();
        }
        boolean persistable = persistableOutput(event);
        boolean interactionControl = controlEvent(event);
        persistableOutputObserved = persistableOutputObserved || persistable;
        nonControlOutputObserved = nonControlOutputObserved || persistable && !interactionControl;
        if (persistenceState.placeholderMode() && !interactionControl) {
            return ObservationResult.accepted();
        }
        if ("message.delta".equals(event.type())) {
            Object delta = event.payload().get("delta");
            if (delta != null) {
                return appendDelta(String.valueOf(delta));
            }
            return ObservationResult.accepted();
        }
        if ("message.snapshot".equals(event.type())) {
            Object content = event.payload().get("content");
            String requested = content == null ? null : String.valueOf(content);
            ObservationResult bodyResult = replaceSnapshot(requested);
            ChatMessagePartDraft part = snapshotPart(event, snapshot);
            ObservationResult partResult = retainPart(part, false);
            if (bodyResult.essentialOverflow()) {
                return bodyResult;
            }
            return partResult;
        }
        if (event.type() != null && event.type().startsWith("runtime.")) {
            if (isTransientIntentProcessEvent(event.payload())) {
                return ObservationResult.accepted();
            }
            if (isIntentClarificationResponse(event.payload())) {
                if (!AmbiguousRouteSupport.isAmbiguous(event.payload())) {
                    return ObservationResult.accepted();
                }
            }
            if (isDomainAgentRefusal(event.payload())) {
                // 拒答前已经输出的正文只作为 MESSAGE_SNAPSHOT/过程 part 保留，不能与新 Agent
                // 的回答拼成最终 content。
                clearBody();
            }
            ChatMessagePartDraft part = runtimePart(event);
            boolean processPart = processPart(part);
            ObservationResult partResult = retainPart(part, processPart);
            if (partResult.essentialOverflow()) {
                return partResult;
            }
            ObservationResult fallbackResult = ObservationResult.accepted();
            if (isDomainAgentRefusal(event.payload())) {
                fallbackResult = replaceStructuredFallback(firstText(event.payload(),
                        "reason", "userMessage", "reasonCode", "code"));
            }
            if (isIntentClarificationRequest(event.payload())) {
                fallbackResult = replaceStructuredFallback(intentClarificationQuestion(event.payload()));
            }
            if (isRouteSwitchConfirmationRequest(event.payload()) || isRouteSwitchDeclined(event.payload())) {
                if (isRouteSwitchConfirmationRequest(event.payload())) {
                    clearBody();
                }
                fallbackResult = replaceStructuredFallback(
                        firstText(event.payload(), "message", "reason", "sourceType"));
            }
            if (fallbackResult.essentialOverflow()) {
                return fallbackResult;
            }
            return partResult;
        }
        return ObservationResult.accepted();
    }

    synchronized boolean shouldPersistMessage() {
        if (persistenceState.placeholderMode()) {
            return persistenceState.runtimeDispatchStarted()
                    || persistableOutputObserved
                    || persistedParts().stream().anyMatch(AssistantAssembly::userVisiblePart);
        }
        return hasContent() || persistedParts().stream().anyMatch(AssistantAssembly::userVisiblePart);
    }

    /**
     * 判断资源超限时是否确有本轮部分业务输出可保存。
     *
     * <p>Interaction 的选择、回答和确认响应只是续跑控制事实，不能据此覆盖等待态 assistant。
     * 占位策略不会保留真实业务正文，因此使用运行期观察标记判断。</p>
     */
    synchronized boolean hasResourceLimitPartialOutput() {
        if (persistenceState.placeholderMode()) {
            return nonControlOutputObserved;
        }
        return hasContent() || persistedParts().stream()
                .anyMatch(part -> userVisiblePart(part) && !controlPart(part));
    }

    /** stop续跑时判断本轮是否已经产生可替换原assistant正文的业务输出。 */
    synchronized boolean hasStopBusinessOutput() {
        return hasResourceLimitPartialOutput();
    }

    /** stop续跑时判断本轮是否至少产生一个需要追加到原assistant的控制Part。 */
    synchronized boolean hasStopControlOutput() {
        return persistedParts().stream().anyMatch(part -> userVisiblePart(part) && controlPart(part));
    }

    synchronized String finalContent() {
        if (persistenceState.placeholderMode()) {
            return persistenceState.placeholderContent();
        }
        if (snapshot != null) {
            return snapshot;
        }
        if (!deltaDraft.isEmpty()) {
            return deltaDraft.toString();
        }
        return structuredFallbackContent == null ? "" : structuredFallbackContent;
    }

    synchronized List<ChatMessagePartDraft> parts() {
        return List.copyOf(persistedParts());
    }

    boolean appendAnswerPart() {
        return !persistenceState.placeholderMode();
    }

    String assistantMetadata(String metadataJson) {
        return AgentDataPersistenceMetadata.mergeAssistantMetadata(metadataJson, persistenceState);
    }

    AgentDataPersistenceState persistenceState() {
        return persistenceState;
    }

    /**
     * 冻结stop终态要读取的投影。取消订阅后，已经开始的阻塞事件提交仍可能迟到返回；
     * 冻结后这些事件继续保留其Event事实，但不得再修改assistant或重新占用汇总预算。
     */
    synchronized void sealForStop() {
        stopSealed = true;
    }

    static List<ChatMessagePartDraft> controlParts(List<ChatMessagePartDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return List.of();
        }
        return drafts.stream().filter(AssistantAssembly::controlPart).toList();
    }

    private List<ChatMessagePartDraft> persistedParts() {
        if (!persistenceState.placeholderMode()) {
            return parts.stream().map(PartEntry::draft).toList();
        }
        return parts.stream()
                .map(PartEntry::draft)
                .filter(AssistantAssembly::controlPart)
                .toList();
    }

    RuntimeStreamLimitExceededException overflowException(ObservationResult result) {
        RuntimeStreamLimitType type = result == null || result.limitType() == null
                ? RuntimeStreamLimitType.ASSISTANT_BYTES
                : result.limitType();
        return new RuntimeStreamLimitExceededException(type,
                "Assistant历史投影超过硬上限: runId=" + runId() + ", limitType=" + type);
    }

    private ObservationResult appendDelta(String value) {
        if (value == null || value.isEmpty() || snapshot != null) {
            return ObservationResult.accepted();
        }
        ReservedText accepted = reserveTextPrefix(value);
        if (!accepted.value().isEmpty()) {
            deltaDraft.append(accepted.value());
            bodyBytes += accepted.bytes();
        }
        if (accepted.truncated()) {
            return ObservationResult.overflow(lastByteLimit());
        }
        return ObservationResult.accepted();
    }

    private ObservationResult replaceSnapshot(String value) {
        clearBody();
        if (value == null) {
            return ObservationResult.accepted();
        }
        ReservedText accepted = reserveTextPrefix(value);
        snapshot = accepted.value();
        bodyBytes = accepted.bytes();
        return accepted.truncated()
                ? ObservationResult.overflow(lastByteLimit())
                : ObservationResult.accepted();
    }

    private ObservationResult replaceStructuredFallback(String value) {
        releaseStructuredFallback();
        if (value == null || value.isEmpty()) {
            structuredFallbackContent = value;
            return ObservationResult.accepted();
        }
        ReservedText accepted = reserveTextPrefix(value);
        structuredFallbackContent = accepted.value();
        structuredFallbackBytes = accepted.bytes();
        return accepted.truncated()
                ? ObservationResult.overflow(lastByteLimit())
                : ObservationResult.accepted();
    }

    private ObservationResult retainPart(ChatMessagePartDraft part, boolean process) {
        if (part == null || budget == null || budgetRegistry == null) {
            parts.add(new PartEntry(part, process, 0L));
            return ObservationResult.accepted();
        }
        long bytes = budgetRegistry.serializedBytes(part);
        AssistantAssemblyBudgetRegistry.ReserveResult reservation = budget.reservePart(bytes, process);
        if (reservation.accepted()) {
            parts.add(new PartEntry(part, process, bytes));
            return ObservationResult.accepted();
        }
        if (process) {
            recordFiltered(bytes);
            return ObservationResult.filtered();
        }
        while (!budget.canReserveEssentialPart(bytes) && evictOldestProcessPart()) {
            // 只淘汰当前run最早的过程Part，为正文和交互控制事实让出空间。
        }
        reservation = budget.reservePart(bytes, false);
        if (!reservation.accepted()) {
            return ObservationResult.overflow(reservation.limitType());
        }
        parts.add(new PartEntry(part, false, bytes));
        return ObservationResult.accepted();
    }

    private ReservedText reserveTextPrefix(String requested) {
        if (requested == null || requested.isEmpty()) {
            return new ReservedText(requested == null ? "" : requested, 0L, false);
        }
        long bytes = Utf8Text.bytes(requested);
        if (budget == null) {
            return new ReservedText(requested, bytes, false);
        }
        while (!budget.canReserveEssentialBytes(bytes) && evictOldestProcessPart()) {
            // 正文优先于过程Part，按最旧优先淘汰。
        }
        AssistantAssemblyBudgetRegistry.ByteReservation reservation =
                budget.reserveEssentialBytesUpTo(bytes);
        lastByteLimit = reservation.overflowType();
        if (reservation.bytes() >= bytes) {
            return new ReservedText(requested, bytes, false);
        }
        Utf8Text.Prefix accepted = Utf8Text.prefix(requested, reservation.bytes());
        if (reservation.bytes() > accepted.bytes()) {
            budget.releaseBytes(reservation.bytes() - accepted.bytes());
        }
        return new ReservedText(accepted.value(), accepted.bytes(), accepted.truncated());
    }

    private RuntimeStreamLimitType lastByteLimit;

    private RuntimeStreamLimitType lastByteLimit() {
        return lastByteLimit == null ? RuntimeStreamLimitType.ASSISTANT_BYTES : lastByteLimit;
    }

    private boolean evictOldestProcessPart() {
        Iterator<PartEntry> iterator = parts.iterator();
        while (iterator.hasNext()) {
            PartEntry entry = iterator.next();
            if (!entry.process()) {
                continue;
            }
            iterator.remove();
            if (budget != null) {
                budget.releasePart(entry.bytes(), true);
            }
            evictedPartCount++;
            evictedPartBytes += entry.bytes();
            return true;
        }
        return false;
    }

    private void clearBody() {
        // setLength(0)会继续持有历史大数组；替换实例后再释放逻辑预算。
        deltaDraft = new StringBuilder();
        snapshot = null;
        long releasedBytes = bodyBytes;
        bodyBytes = 0L;
        if (budget != null && releasedBytes > 0L) {
            budget.releaseBytes(releasedBytes);
        }
    }

    private void releaseStructuredFallback() {
        if (budget != null && structuredFallbackBytes > 0L) {
            budget.releaseBytes(structuredFallbackBytes);
        }
        structuredFallbackBytes = 0L;
        structuredFallbackContent = null;
    }

    private void recordFiltered(long bytes) {
        filteredPartCount++;
        filteredPartBytes += Math.max(0L, bytes);
        if (!filterWarningLogged) {
            filterWarningLogged = true;
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.RESOURCE_EXHAUSTED,
                            "Assistant process part was filtered by runtime stream limits")
                    .runId(runId())
                    .operation("assistant-assembly.filter")
                    .attribute("filteredParts", filteredPartCount)
                    .attribute("filteredBytes", filteredPartBytes)
                    .build());
        }
    }

    private String runId() {
        return budget == null ? null : budget.runId();
    }

    private record ReservedText(String value, long bytes, boolean truncated) {
    }

    private static boolean processPart(ChatMessagePartDraft part) {
        if (part == null || part.partType() == null) {
            return true;
        }
        return switch (part.partType()) {
            case "PROGRESS", "THINKING", "AGENT", "METADATA", "RUNTIME_EVENT" -> true;
            default -> false;
        };
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        stopSealed = true;
        if (budget != null) {
            budget.close();
        }
        if (filteredPartCount > 0L || evictedPartCount > 0L) {
            log.info("Assistant assembly stream-limit summary. runId={}, filteredParts={}, filteredBytes={}, "
                            + "evictedParts={}, evictedBytes={}",
                    runId(), filteredPartCount, filteredPartBytes, evictedPartCount, evictedPartBytes);
        }
    }

    private boolean hasContent() {
        return snapshot != null && !snapshot.isEmpty()
                || !deltaDraft.isEmpty()
                || structuredFallbackContent != null && !structuredFallbackContent.isEmpty();
    }

    private static boolean userVisiblePart(ChatMessagePartDraft part) {
        if (part == null || part.partType() == null) {
            return false;
        }
        return switch (part.partType()) {
            case "PROGRESS", "AGENT", "THINKING", "TOOL", "REFERENCE", "CARD",
                 "CLARIFICATION_REQUEST", "CLARIFICATION_RESPONSE",
                 "AGENT_CLARIFICATION_REQUEST", "AGENT_CLARIFICATION_RESPONSE",
                 "INTENT_CLARIFICATION_REQUEST", "INTENT_CLARIFICATION_RESPONSE",
                 "DOMAIN_AGENT_REFUSAL", "ROUTE_SWITCH_CONFIRMATION_REQUEST",
                 "ROUTE_SWITCH_CONFIRMATION_RESPONSE", "ROUTE_SWITCH_DECLINED" -> true;
            default -> false;
        };
    }

    private static boolean controlPart(ChatMessagePartDraft part) {
        if (part == null || part.partType() == null) {
            return false;
        }
        return switch (part.partType()) {
            case "CLARIFICATION_REQUEST", "CLARIFICATION_RESPONSE",
                 "AGENT_CLARIFICATION_REQUEST", "AGENT_CLARIFICATION_RESPONSE",
                 "INTENT_CLARIFICATION_REQUEST", "INTENT_CLARIFICATION_RESPONSE",
                 "ROUTE_SWITCH_CONFIRMATION_REQUEST", "ROUTE_SWITCH_CONFIRMATION_RESPONSE",
                 "ROUTE_SWITCH_DECLINED" -> true;
            default -> false;
        };
    }

    private static boolean controlEvent(ChatEvent event) {
        if (event == null || event.type() == null || !event.type().startsWith("runtime.")) {
            return false;
        }
        return controlPart(runtimePart(event));
    }

    private static boolean persistableOutput(ChatEvent event) {
        if ("message.delta".equals(event.type())) {
            return nonEmpty(event.payload().get("delta"));
        }
        if ("message.snapshot".equals(event.type())) {
            return nonEmpty(event.payload().get("content"));
        }
        return event.type() != null
                && event.type().startsWith("runtime.")
                && !isTransientIntentProcessEvent(event.payload())
                && userVisiblePart(runtimePart(event));
    }

    private static boolean nonEmpty(Object value) {
        return value != null && !String.valueOf(value).isEmpty();
    }

    private static ChatMessagePartDraft runtimePart(ChatEvent event) {
        Map<String, Object> payload = eventPartPayload(event);
        String sourceType = stringValue(payload.get("sourceType"));
        return new ChatMessagePartDraft(partType(event.type(), payload), sourceType, contentText(event.type(), payload), payload);
    }

    private static ChatMessagePartDraft snapshotPart(ChatEvent event, String retainedContent) {
        Map<String, Object> payload = eventPartPayload(event);
        if (retainedContent != null) {
            payload.put("content", retainedContent);
        }
        String sourceType = stringValue(payload.get("sourceType"));
        if (sourceType == null || sourceType.isBlank()) {
            sourceType = "message.snapshot";
        }
        return new ChatMessagePartDraft(
                "MESSAGE_SNAPSHOT",
                sourceType,
                stringValue(payload.get("content")),
                "回答快照",
                "INFO",
                "answer",
                "collapsible",
                false,
                payload
        );
    }

    private static Map<String, Object> eventPartPayload(ChatEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (event.payload() != null) {
            payload.putAll(event.payload());
        }
        payload.put("serverTimestampMs", event.createdAt().toEpochMilli());
        return payload;
    }

    private static String partType(String eventType, Map<String, Object> payload) {
        if ("runtime.card".equals(eventType) && isQuestionnaireApprovalRequest(payload)) {
            return "AGENT_CLARIFICATION_REQUEST";
        }
        if ("runtime.card".equals(eventType) && isClarificationResponse(payload)) {
            return agentClarificationResponse(payload) ? "AGENT_CLARIFICATION_RESPONSE" : "CLARIFICATION_RESPONSE";
        }
        if ("runtime.card".equals(eventType) && isIntentClarificationRequest(payload)) {
            return "INTENT_CLARIFICATION_REQUEST";
        }
        if ("runtime.card".equals(eventType) && isIntentClarificationResponse(payload)) {
            return "INTENT_CLARIFICATION_RESPONSE";
        }
        if ("runtime.card".equals(eventType) && isRouteSwitchConfirmationRequest(payload)) {
            return "ROUTE_SWITCH_CONFIRMATION_REQUEST";
        }
        if ("runtime.card".equals(eventType) && isRouteSwitchConfirmationResponse(payload)) {
            return "ROUTE_SWITCH_CONFIRMATION_RESPONSE";
        }
        if ("runtime.card".equals(eventType) && isRouteSwitchDeclined(payload)) {
            return "ROUTE_SWITCH_DECLINED";
        }
        if (isDomainAgentRefusal(payload)) {
            return "DOMAIN_AGENT_REFUSAL";
        }
        return switch (eventType) {
            case "runtime.progress" -> "PROGRESS";
            case "runtime.metadata" -> "METADATA";
            case "runtime.agent" -> "AGENT";
            case "runtime.thinking" -> "THINKING";
            case "runtime.tool" -> "TOOL";
            case "runtime.reference" -> "REFERENCE";
            case "runtime.card" -> "CARD";
            default -> "RUNTIME_EVENT";
        };
    }

    private static String contentText(String eventType, Map<String, Object> payload) {
        if ("runtime.progress".equals(eventType)) {
            return firstText(payload, "text", "message");
        }
        if ("runtime.agent".equals(eventType)) {
            return firstText(payload, "task", "agentName");
        }
        if ("runtime.tool".equals(eventType)) {
            String toolName = firstText(payload, "toolName");
            String preview = firstText(payload, "inputPreview");
            if (toolName != null && preview != null) {
                return toolName + ": " + preview;
            }
            return toolName == null ? preview : toolName;
        }
        if ("runtime.thinking".equals(eventType)) {
            String text = firstText(payload, "text", "title");
            if (text != null) {
                return text;
            }
            String status = firstText(payload, "status");
            String operationId = firstText(payload, "operationId");
            return operationId == null ? status : status + ": " + operationId;
        }
        if ("runtime.metadata".equals(eventType)) {
            if (isDomainAgentRefusal(payload)) {
                return firstText(payload, "reason", "userMessage", "reasonCode", "code");
            }
            return firstText(payload, "projectHome", "metadataType");
        }
        if ("runtime.reference".equals(eventType)) {
            return firstText(payload, "delta", "title", "url", "referenceType", "sourceType");
        }
        if ("runtime.card".equals(eventType)) {
            if (isQuestionnaireApprovalRequest(payload)) {
                return firstText(payload, "title", "message", "detail", "sourceType");
            }
            if (isClarificationResponse(payload)) {
                return firstText(payload, "answerText", "sourceType");
            }
            if (isIntentClarificationRequest(payload)) {
                return intentClarificationQuestion(payload);
            }
            if (isIntentClarificationResponse(payload)) {
                return firstText(payload, "answerText", "sourceType");
            }
            if (isRouteSwitchConfirmationRequest(payload)) {
                return firstText(payload, "message", "candidateIntentName", "candidateTargetId", "sourceType");
            }
            if (isRouteSwitchConfirmationResponse(payload)) {
                return firstText(payload, "message", "candidateTargetId", "sourceType");
            }
            if (isRouteSwitchDeclined(payload)) {
                return firstText(payload, "message", "currentTargetId", "sourceType");
            }
            return firstText(payload, "delta", "cardUrl", "intent", "domainAgentId", "skillId", "cardType", "sourceType");
        }
        return firstText(payload, "text", "displayText", "sourceType");
    }

    private static boolean isQuestionnaireApprovalRequest(Map<String, Object> payload) {
        return "approval-request".equals(stringValue(payload.get("sourceType")))
                && "questionnaire".equalsIgnoreCase(stringValue(payload.get("operation_type")));
    }

    private static boolean isClarificationResponse(Map<String, Object> payload) {
        return "clarification-response".equals(stringValue(payload.get("sourceType")));
    }

    private static boolean agentClarificationResponse(Map<String, Object> payload) {
        return "AGENT_CLARIFICATION".equals(stringValue(payload.get("interactionType")))
                || "CLARIFICATION".equals(stringValue(payload.get("interactionType")));
    }

    private static boolean isIntentClarificationRequest(Map<String, Object> payload) {
        return "intent-clarification-request".equals(stringValue(payload.get("sourceType")));
    }

    private static boolean isIntentClarificationResponse(Map<String, Object> payload) {
        return "intent-clarification-response".equals(stringValue(payload.get("sourceType")));
    }

    private static boolean isTransientIntentProcessEvent(Map<String, Object> payload) {
        if (!"intent-agent".equals(stringValue(payload.get("source")))) {
            return false;
        }
        String sourceType = stringValue(payload.get("sourceType"));
        return "intent-start".equals(sourceType)
                || "intent-progress".equals(sourceType)
                || "intent-delta".equals(sourceType);
    }

    private static String intentClarificationQuestion(Map<String, Object> payload) {
        String direct = firstText(payload, "clarifyQuestion", "question", "message", "detail");
        if (direct != null) {
            return direct;
        }
        Object clarification = payload == null ? null : payload.get("clarification");
        if (clarification instanceof Map<?, ?> map) {
            return firstText(castStringMap(map), "clarifyQuestion", "question", "message");
        }
        return null;
    }

    private static Map<String, Object> castStringMap(Map<?, ?> source) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null) {
                result.put(String.valueOf(key), value);
            }
        });
        return result;
    }

    private static boolean isRouteSwitchConfirmationRequest(Map<String, Object> payload) {
        return "route-switch-confirmation-request".equals(stringValue(payload.get("sourceType")));
    }

    private static boolean isRouteSwitchConfirmationResponse(Map<String, Object> payload) {
        return "route-switch-confirmation-response".equals(stringValue(payload.get("sourceType")));
    }

    private static boolean isRouteSwitchDeclined(Map<String, Object> payload) {
        return "route-switch-declined".equals(stringValue(payload.get("sourceType")));
    }

    private static boolean isDomainAgentRefusal(Map<String, Object> payload) {
        return "agent.refusal".equals(stringValue(payload.get("sourceType")))
                && "domain_agent_control".equals(stringValue(payload.get("metadataType")))
                && "REROUTE".equals(stringValue(payload.get("supervisorAction")));
    }

    private static String firstText(Map<String, Object> payload, String... keys) {
        for (String key : keys) {
            String value = stringValue(payload.get(key));
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    record ObservationResult(Status status, RuntimeStreamLimitType limitType) {
        static ObservationResult accepted() {
            return new ObservationResult(Status.ACCEPTED, null);
        }

        static ObservationResult filtered() {
            return new ObservationResult(Status.FILTERED, null);
        }

        static ObservationResult overflow(RuntimeStreamLimitType type) {
            return new ObservationResult(Status.ESSENTIAL_OVERFLOW,
                    type == null ? RuntimeStreamLimitType.ASSISTANT_BYTES : type);
        }

        boolean essentialOverflow() {
            return status == Status.ESSENTIAL_OVERFLOW;
        }
    }

    enum Status {
        ACCEPTED,
        FILTERED,
        ESSENTIAL_OVERFLOW
    }

    private record PartEntry(ChatMessagePartDraft draft, boolean process, long bytes) {
    }
}
