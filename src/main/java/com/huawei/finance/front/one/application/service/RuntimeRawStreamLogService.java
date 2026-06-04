package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.application.config.RuntimeRawStreamLogProperties;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeRequest;
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
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Runtime 原始流响应日志服务。
 *
 * <p>该服务在 Relay normalizer 之前捕获原始响应文本，并以独立诊断日志形式保存。
 * 日志写入失败会被吞掉并记录 warn，不能影响标准 ChatEvent 入库、WebSocket 推送或 run 生命周期。</p>
 */
@Service
public class RuntimeRawStreamLogService {
    private static final Logger log = LoggerFactory.getLogger(RuntimeRawStreamLogService.class);
    private static final String TRUNCATED_SUFFIX = "...[TRUNCATED]";
    private static final Pattern SENSITIVE_JSON_FIELD = Pattern.compile(
            "(?i)(\"(?:cookie|authorization|auth|token|secret|password|credential|api_key|apikey|access_key)\"\\s*:\\s*\")([^\"]*)(\")");

    private final RuntimeRawStreamLogProperties properties;
    private final RuntimeRawStreamLogRepository repository;
    private final IdGenerator idGenerator;

    public RuntimeRawStreamLogService(RuntimeRawStreamLogProperties properties,
                                      RuntimeRawStreamLogRepository repository,
                                      IdGenerator idGenerator) {
        this.properties = properties;
        this.repository = repository;
        this.idGenerator = idGenerator;
    }

    /**
     * 捕获并异步保存 Runtime 原始响应，同时原样透传 chunk 给 normalizer。
     *
     * @param source 下游返回的原始文本流。
     * @param request 本轮 Runtime 请求，提供 run/session/owner 归属。
     * @param runtimeProvider Runtime provider，例如 relay。
     * @param apiAdapter API adapter，例如 relay-stream-http。
     * @return 原样透传的文本流。
     */
    public Flux<String> capture(Flux<String> source, AgentRuntimeRequest request,
                                String runtimeProvider, String apiAdapter) {
        if (!properties.isEnabled() || request == null || properties.normalizedMaxRowsPerRun() == 0) {
            return source;
        }
        return Flux.defer(() -> {
            CaptureState state = new CaptureState(request, runtimeProvider, apiAdapter);
            return source
                    .doOnNext(state::safeOnChunk)
                    .doOnError(ignored -> state.safeComplete())
                    .doOnComplete(state::safeComplete)
                    .doFinally(ignored -> state.safeClose());
        });
    }

    private final class CaptureState {
        private final AgentRuntimeRequest request;
        private final String runtimeProvider;
        private final String apiAdapter;
        private final StringBuilder buffer = new StringBuilder();
        private int bufferSourceLength;
        private boolean bufferTruncated;
        private int bufferChunkCount;
        private long chunkIndex;
        private int rowsWritten;
        private Disposable timer;
        private boolean closed;
        private boolean failureLogged;

        private CaptureState(AgentRuntimeRequest request, String runtimeProvider, String apiAdapter) {
            this.request = request;
            this.runtimeProvider = runtimeProvider;
            this.apiAdapter = apiAdapter;
        }

        /**
         * 捕获链路是诊断旁路。这里吞掉所有运行时异常，并关闭本轮 raw log 采集，避免日志故障
         * 反向导致 Runtime 主响应流失败。
         */
        private void safeOnChunk(String chunk) {
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

        private void safeClose() {
            try {
                close();
            } catch (RuntimeException ex) {
                disableRawLog("close capture failed", ex);
            }
        }

        private synchronized void onChunk(String chunk) {
            if (closed || chunk == null || chunk.isEmpty() || capacityExhausted()) {
                return;
            }
            RuntimeRawStreamChunk rawChunk = new RuntimeRawStreamChunk(
                    request.tenantId(), request.userId(), request.sessionId(), request.runId(),
                    runtimeProvider, apiAdapter, chunk, Instant.now());
            boolean terminal = isTerminalChunk(rawChunk.content());
            SanitizedContent sanitized = sanitizeAndMaybeHardTruncate(rawChunk.content());
            String content = sanitized.content();
            if (content.length() > properties.normalizedMaxChars()) {
                flush(false);
                saveSplitContent(content, rawChunk.content().length(), terminal, sanitized.truncated());
                return;
            }
            if (!buffer.isEmpty() && buffer.length() + content.length() > properties.normalizedMaxChars()) {
                flush(false);
            }
            buffer.append(content);
            bufferSourceLength += rawChunk.content().length();
            bufferTruncated = bufferTruncated || sanitized.truncated();
            bufferChunkCount++;
            if (terminal) {
                flush(true);
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
                save(content.substring(start, end), sourceLength, 1, index + 1, partCount, truncated,
                        terminal && lastPart);
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
            save(buffer.toString(), bufferSourceLength, bufferChunkCount, 0, 0, bufferTruncated, terminal);
            buffer.setLength(0);
            bufferSourceLength = 0;
            bufferTruncated = false;
            bufferChunkCount = 0;
        }

        private void save(String rawContent, int sourceContentLength, int chunkCount,
                          int splitPartIndex, int splitPartCount, boolean truncated, boolean terminal) {
            if (capacityExhausted()) {
                return;
            }
            rowsWritten++;
            RuntimeRawStreamLogEntry entry = new RuntimeRawStreamLogEntry(
                    idGenerator.newId("rawlog", IdGenerateContext.of(request.tenantId(), request.userId(),
                            request.sessionId(), request.runId())),
                    request.tenantId(),
                    request.userId(),
                    request.sessionId(),
                    request.runId(),
                    runtimeProvider,
                    apiAdapter,
                    ++chunkIndex,
                    rawContent,
                    sha256(rawContent),
                    rawContent == null ? 0 : rawContent.length(),
                    sourceContentLength,
                    chunkCount,
                    splitPartIndex,
                    splitPartCount,
                    truncated,
                    terminal,
                    Instant.now()
            );
            Schedulers.boundedElastic().schedule(() -> {
                try {
                    repository.save(entry);
                } catch (RuntimeException ex) {
                    log.warn("Runtime raw stream log write failed. runId={}, chunkIndex={}, reason={}",
                            entry.runId(), entry.chunkIndex(), ex.getMessage());
                }
            });
        }

        private boolean capacityExhausted() {
            return rowsWritten >= properties.normalizedMaxRowsPerRun();
        }

        private SanitizedContent sanitizeAndMaybeHardTruncate(String chunk) {
            String content = properties.isRedactSensitiveFields()
                    ? SENSITIVE_JSON_FIELD.matcher(chunk).replaceAll("$1[REDACTED]$3")
                    : chunk;
            int hardMaxChars = properties.normalizedHardMaxChars();
            if (content.length() <= hardMaxChars) {
                return new SanitizedContent(content, false);
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
                        request.runId(), reason, ex.getMessage());
            }
        }
    }

    private boolean isTerminalChunk(String chunk) {
        if (chunk == null) {
            return false;
        }
        String normalized = chunk.trim().toLowerCase();
        return normalized.equals("[done]")
                || normalized.equals("steam-complete")
                || normalized.equals("stream-complete")
                || normalized.equals("stream_complete")
                || normalized.equals("stream.complete")
                || normalized.equals("stream-completed")
                || normalized.contains("data: [done]")
                || normalized.contains("steam-complete")
                || normalized.contains("stream-complete");
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
}
