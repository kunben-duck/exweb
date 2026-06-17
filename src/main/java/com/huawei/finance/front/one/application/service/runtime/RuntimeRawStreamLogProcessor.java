package com.huawei.finance.front.one.application.service.runtime;

import com.huawei.finance.front.one.application.config.RuntimeRawStreamLogProperties;
import com.huawei.finance.front.one.application.integration.conversation.RuntimeRawStreamLogConsumer;
import com.huawei.finance.front.one.application.integration.conversation.RuntimeRawStreamLogRepository;
import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.domain.chat.RuntimeRawStreamChunk;
import com.huawei.finance.front.one.domain.chat.RuntimeRawStreamLogEntry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

/**
 * Runtime raw chunk 消费处理器。
 *
 * <p>企业 MQ listener 或其他消息队列 consumer 将原始 chunk 交给该处理器。合并、脱敏、
 * hash、分片和数据库写入都在消费端完成，因此这些诊断工作不会阻塞 Relay normalizer
 * 到 ChatEvent/WebSocket 的主链路。</p>
 */
@Service
public class RuntimeRawStreamLogProcessor implements RuntimeRawStreamLogConsumer {
    private static final Logger log = LoggerFactory.getLogger(RuntimeRawStreamLogProcessor.class);
    private static final String TRUNCATED_SUFFIX = "...[TRUNCATED]";
    private static final Pattern SENSITIVE_JSON_FIELD = Pattern.compile(
            "(?i)(\"(?:cookie|authorization|auth|token|secret|password|credential|api_key|apikey|access_key)\"\\s*:\\s*\")([^\"]*)(\")");

    private final RuntimeRawStreamLogProperties properties;
    private final RuntimeRawStreamLogRepository repository;
    private final IdGenerator idGenerator;
    private final ConcurrentMap<StateKey, CaptureState> states = new ConcurrentHashMap<>();

    public RuntimeRawStreamLogProcessor(RuntimeRawStreamLogProperties properties,
                                        RuntimeRawStreamLogRepository repository,
                                        IdGenerator idGenerator) {
        this.properties = properties;
        this.repository = repository;
        this.idGenerator = idGenerator;
    }

    /**
     * 消费一条 MQ raw chunk。
     *
     * <p>任何处理异常都会被吞掉并记录日志。raw log 是诊断数据，不能因为消费端异常触发
     * 消息队列反复重试而拖垮服务。</p>
     *
     * @param chunk MQ 中收到的 Runtime 原始响应片段。
     */
    @Override
    public void consume(RuntimeRawStreamChunk chunk) {
        if (!properties.isEnabled() || chunk == null || chunk.content() == null || chunk.content().isEmpty()
                || properties.normalizedMaxRowsPerRun() == 0) {
            return;
        }
        try {
            StateKey key = StateKey.from(chunk);
            CaptureState state = states.get(key);
            if (state == null) {
                if (states.size() >= properties.normalizedConsumerMaxActiveRunBuffers()) {
                    writeImmediate(chunk);
                    return;
                }
                state = states.computeIfAbsent(key, ignored -> new CaptureState(key));
            }
            state.safeOnChunk(chunk);
            if (state.isClosed()) {
                states.remove(key, state);
            }
        } catch (RuntimeException ex) {
            log.warn("Runtime raw stream chunk consume failed. runId={}, chunkIndex={}, reason={}",
                    chunk.runId(), chunk.chunkIndex(), ex.getMessage());
        }
    }

    /**
     * 清理缺失终态的 run buffer。
     *
     * <p>正常情况下 terminal chunk 会立即 flush 并释放状态；该兜底用于 MQ 重放中断或下游未
     * 发送终态的场景，避免消费端为 raw log 合并长期占用内存。</p>
     */
    @Scheduled(fixedDelayString = "${financeex.runtime-raw-log.consumer-state-cleanup-delay-ms:60000}")
    public void cleanupIdleStates() {
        if (states.isEmpty()) {
            return;
        }
        Instant deadline = Instant.now().minus(properties.normalizedConsumerStateIdleTtl());
        for (Map.Entry<StateKey, CaptureState> entry : states.entrySet()) {
            CaptureState state = entry.getValue();
            if (state.lastTouched().isBefore(deadline)) {
                state.safeComplete();
                states.remove(entry.getKey(), state);
            }
        }
    }

    private void writeImmediate(RuntimeRawStreamChunk chunk) {
        StateKey key = StateKey.from(chunk);
        CaptureState state = new CaptureState(key);
        state.safeOnChunk(chunk);
        state.safeComplete();
    }

    private final class CaptureState {
        private final StateKey key;
        private final StringBuilder buffer = new StringBuilder();
        private int bufferSourceLength;
        private boolean bufferTruncated;
        private int bufferChunkCount;
        private long chunkIndex;
        private int rowsWritten;
        private Disposable timer;
        private boolean closed;
        private boolean failureLogged;
        private Instant lastTouched = Instant.now();

        private CaptureState(StateKey key) {
            this.key = key;
        }

        private void safeOnChunk(RuntimeRawStreamChunk chunk) {
            try {
                onChunk(chunk);
            } catch (RuntimeException ex) {
                disableRawLog("capture chunk failed", ex);
            }
        }

        private void safeComplete() {
            try {
                complete();
            } catch (RuntimeException ex) {
                disableRawLog("complete capture failed", ex);
            }
        }

        private synchronized void onChunk(RuntimeRawStreamChunk chunk) {
            if (closed || chunk == null || chunk.content() == null || chunk.content().isEmpty() || capacityExhausted()) {
                return;
            }
            lastTouched = Instant.now();
            boolean terminal = chunk.terminalCandidate() || RuntimeRawStreamTerminalDetector.isTerminalChunk(chunk.content());
            SanitizedContent sanitized = sanitizeAndMaybeHardTruncate(chunk.content(), chunk.truncated());
            String content = sanitized.content();
            int sourceLength = chunk.sourceContentLength() > 0 ? chunk.sourceContentLength() : chunk.content().length();
            if (content.length() > properties.normalizedMaxChars()) {
                flush(false);
                saveSplitContent(content, sourceLength, terminal, sanitized.truncated());
                if (terminal) {
                    close();
                }
                return;
            }
            if (!buffer.isEmpty() && buffer.length() + content.length() > properties.normalizedMaxChars()) {
                flush(false);
            }
            buffer.append(content);
            bufferSourceLength += sourceLength;
            bufferTruncated = bufferTruncated || sanitized.truncated();
            bufferChunkCount++;
            if (terminal) {
                flush(true);
                close();
            } else {
                scheduleFlush();
            }
        }

        private synchronized void complete() {
            if (closed) {
                return;
            }
            flush(false);
            close();
        }

        private synchronized void close() {
            closed = true;
            cancelTimer();
            buffer.setLength(0);
            bufferSourceLength = 0;
            bufferTruncated = false;
            bufferChunkCount = 0;
        }

        private void saveSplitContent(String content, int sourceLength, boolean terminal, boolean hardTruncated) {
            int maxChars = properties.normalizedMaxChars();
            int partCount = (int) Math.ceil(content.length() / (double) maxChars);
            for (int index = 0; index < partCount && !capacityExhausted(); index++) {
                int start = index * maxChars;
                int end = Math.min(content.length(), start + maxChars);
                boolean lastPart = index == partCount - 1;
                boolean truncated = hardTruncated && lastPart;
                save(new RawLogSegment(content.substring(start, end), sourceLength, 1, index + 1, partCount,
                        truncated, terminal && lastPart));
            }
        }

        private void flush(boolean terminal) {
            cancelTimer();
            if (buffer.isEmpty() || capacityExhausted()) {
                buffer.setLength(0);
                bufferSourceLength = 0;
                bufferTruncated = false;
                bufferChunkCount = 0;
                return;
            }
            save(new RawLogSegment(buffer.toString(), bufferSourceLength, bufferChunkCount, 0, 0,
                    bufferTruncated, terminal));
            buffer.setLength(0);
            bufferSourceLength = 0;
            bufferTruncated = false;
            bufferChunkCount = 0;
        }

        private void save(RawLogSegment segment) {
            if (capacityExhausted()) {
                return;
            }
            rowsWritten++;
            try {
                RuntimeRawStreamLogEntry entry = new RuntimeRawStreamLogEntry(
                        idGenerator.newId("rawlog", IdGenerateContext.of(key.tenantId(), key.userId(),
                                key.sessionId(), key.runId())),
                        key.tenantId(),
                        key.userId(),
                        key.sessionId(),
                        key.runId(),
                        key.runtimeProvider(),
                        key.apiAdapter(),
                        ++chunkIndex,
                        segment.rawContent(),
                        sha256(segment.rawContent()),
                        segment.rawContent() == null ? 0 : segment.rawContent().length(),
                        segment.sourceContentLength(),
                        segment.chunkCount(),
                        segment.splitPartIndex(),
                        segment.splitPartCount(),
                        segment.truncated(),
                        segment.terminal(),
                        Instant.now()
                );
                repository.save(entry);
            } catch (RuntimeException ex) {
                if (!failureLogged) {
                    failureLogged = true;
                    log.warn("Runtime raw stream log write failed. runId={}, reason={}", key.runId(), ex.getMessage());
                }
            }
        }

        private boolean capacityExhausted() {
            return rowsWritten >= properties.normalizedMaxRowsPerRun();
        }

        private SanitizedContent sanitizeAndMaybeHardTruncate(String chunk, boolean alreadyTruncated) {
            String content = properties.isRedactSensitiveFields()
                    ? SENSITIVE_JSON_FIELD.matcher(chunk).replaceAll("$1[REDACTED]$3")
                    : chunk;
            int hardMaxChars = properties.normalizedHardMaxChars();
            if (content.length() <= hardMaxChars) {
                return new SanitizedContent(content, alreadyTruncated);
            }
            int keep = Math.max(0, hardMaxChars - TRUNCATED_SUFFIX.length());
            return new SanitizedContent(content.substring(0, keep) + TRUNCATED_SUFFIX, true);
        }

        private void scheduleFlush() {
            Duration window = properties.normalizedCoalesceWindow();
            if (window.isZero()) {
                flush(false);
                return;
            }
            if (timer != null && !timer.isDisposed()) {
                return;
            }
            timer = Schedulers.parallel().schedule(() -> {
                synchronized (CaptureState.this) {
                    try {
                        flush(false);
                    } catch (RuntimeException ex) {
                        disableRawLog("timer flush failed", ex);
                    }
                }
            }, Math.max(1L, window.toMillis()), TimeUnit.MILLISECONDS);
        }

        private void cancelTimer() {
            if (timer != null && !timer.isDisposed()) {
                timer.dispose();
            }
            timer = null;
        }

        private void disableRawLog(String reason, RuntimeException ex) {
            closed = true;
            cancelTimer();
            buffer.setLength(0);
            bufferSourceLength = 0;
            bufferTruncated = false;
            bufferChunkCount = 0;
            if (!failureLogged) {
                failureLogged = true;
                log.warn("Runtime raw stream log disabled for current run. runId={}, reason={}, error={}",
                        key.runId(), reason, ex.getMessage());
            }
        }

        private boolean isClosed() {
            return closed;
        }

        private Instant lastTouched() {
            return lastTouched;
        }
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", ex);
        }
    }

    private record SanitizedContent(String content, boolean truncated) {
    }

    private record RawLogSegment(String rawContent, int sourceContentLength, int chunkCount,
                                 int splitPartIndex, int splitPartCount, boolean truncated, boolean terminal) {
    }

    private record StateKey(String tenantId, String userId, String sessionId, String runId,
                            String runtimeProvider, String apiAdapter) {
        private static StateKey from(RuntimeRawStreamChunk chunk) {
            return new StateKey(
                    Objects.toString(chunk.tenantId(), ""),
                    Objects.toString(chunk.userId(), ""),
                    Objects.toString(chunk.sessionId(), ""),
                    Objects.toString(chunk.runId(), ""),
                    Objects.toString(chunk.runtimeProvider(), ""),
                    Objects.toString(chunk.apiAdapter(), "")
            );
        }
    }
}
