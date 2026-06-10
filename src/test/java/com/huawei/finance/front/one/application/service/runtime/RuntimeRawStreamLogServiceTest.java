package com.huawei.finance.front.one.application.service.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.front.one.application.config.RuntimeRawStreamLogProperties;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.finance.front.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.finance.front.one.application.integration.conversation.RuntimeRawStreamLogPublisher;
import com.huawei.finance.front.one.application.integration.conversation.RuntimeRawStreamLogRepository;
import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.domain.chat.RuntimeRawStreamChunk;
import com.huawei.finance.front.one.domain.chat.RuntimeRawStreamLogEntry;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
class RuntimeRawStreamLogServiceTest {

    @Test
    void capturePublishesRawChunksWithoutChangingMainStream() {
        InMemoryPublisher publisher = new InMemoryPublisher();
        RuntimeRawStreamLogService service = new RuntimeRawStreamLogService(properties(100, 4096), publisher);

        StepVerifier.create(service.capture(Flux.just("a", "steam-complete"), request(), "relay", "relay-stream-http"))
                .expectNext("a", "steam-complete")
                .verifyComplete();

        assertThat(publisher.chunks()).hasSize(2);
        assertThat(publisher.chunks()).extracting(RuntimeRawStreamChunk::chunkIndex)
                .containsExactly(1L, 2L);
        assertThat(publisher.chunks().get(1).terminalCandidate()).isTrue();
    }

    @Test
    void publisherFailureDoesNotAffectMainStream() {
        RuntimeRawStreamLogService service = new RuntimeRawStreamLogService(
                properties(100, 4096),
                chunk -> {
                    throw new IllegalStateException("mq down");
                });

        StepVerifier.create(service.capture(Flux.just("a", "b"), request(), "relay", "relay-stream-http"))
                .expectNext("a", "b")
                .verifyComplete();
    }

    @Test
    void defaultDisabledPropertiesSkipPublisherCompletely() {
        InMemoryPublisher publisher = new InMemoryPublisher();
        RuntimeRawStreamLogService service = new RuntimeRawStreamLogService(new RuntimeRawStreamLogProperties(), publisher);

        StepVerifier.create(service.capture(Flux.just("a", "b"), request(), "relay", "relay-stream-http"))
                .expectNext("a", "b")
                .verifyComplete();

        assertThat(publisher.chunks()).isEmpty();
    }

    @Test
    void disabledTransportSkipsPublisherCompletely() {
        InMemoryPublisher publisher = new InMemoryPublisher();
        RuntimeRawStreamLogProperties properties = properties(100, 4096);
        properties.setTransport("disabled");
        RuntimeRawStreamLogService service = new RuntimeRawStreamLogService(properties, publisher);

        StepVerifier.create(service.capture(Flux.just("a"), request(), "relay", "relay-stream-http"))
                .expectNext("a")
                .verifyComplete();

        assertThat(publisher.chunks()).isEmpty();
    }

    @Test
    void processorCoalescesRawChunksBeforePersisting() {
        InMemoryRepository repository = new InMemoryRepository();
        RuntimeRawStreamLogProcessor processor = processor(repository, properties(100, 4096));

        processor.consume(chunk(1, "a", false));
        processor.consume(chunk(2, "b", true));

        List<RuntimeRawStreamLogEntry> entries = repository.entries();
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().rawContent()).isEqualTo("ab");
        assertThat(entries.getFirst().chunkCount()).isEqualTo(2);
        assertThat(entries.getFirst().terminal()).isTrue();
        assertThat(entries.getFirst().truncated()).isFalse();
    }

    @Test
    void processorSplitsSingleOversizedChunkWithoutMarkingTruncated() {
        InMemoryRepository repository = new InMemoryRepository();
        RuntimeRawStreamLogProcessor processor = processor(repository, properties(4, 100));

        processor.consume(chunk(1, "abcdefghij", true));

        List<RuntimeRawStreamLogEntry> entries = repository.entries();
        assertThat(entries).extracting(RuntimeRawStreamLogEntry::rawContent)
                .containsExactly("abcd", "efgh", "ij");
        assertThat(entries).allMatch(entry -> !entry.truncated());
        assertThat(entries).extracting(RuntimeRawStreamLogEntry::splitPartCount)
                .containsExactly(3, 3, 3);
    }

    @Test
    void processorMarksTerminalChunkAndRedactsSensitiveFields() {
        InMemoryRepository repository = new InMemoryRepository();
        RuntimeRawStreamLogProcessor processor = processor(repository, properties(4096, 4096));

        processor.consume(chunk(1, "{\"token\":\"secret\",\"safe\":\"yes\"}", false));
        processor.consume(chunk(2, "steam-complete", true));

        List<RuntimeRawStreamLogEntry> entries = repository.entries();
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().rawContent())
                .contains("[REDACTED]")
                .doesNotContain("secret");
        assertThat(entries.getFirst().terminal()).isTrue();
    }

    @Test
    void processorMarksHardLimitDiscardAsTruncated() {
        InMemoryRepository repository = new InMemoryRepository();
        RuntimeRawStreamLogProcessor processor = processor(repository, properties(10, 20));

        processor.consume(chunk(1, "abcdefghijklmnopqrstuvwxyz1234", true));

        List<RuntimeRawStreamLogEntry> entries = repository.entries();
        assertThat(entries).hasSize(2);
        assertThat(entries.stream().map(RuntimeRawStreamLogEntry::rawContent).reduce("", String::concat))
                .contains("[TRUNCATED]");
        assertThat(entries).anyMatch(RuntimeRawStreamLogEntry::truncated);
        assertThat(entries).extracting(RuntimeRawStreamLogEntry::sourceContentLength)
                .containsOnly(30);
    }

    @Test
    void processorWriteFailureDoesNotThrowToMqListener() {
        RuntimeRawStreamLogProcessor processor = processor(new FailingRepository(), properties(100, 4096));

        processor.consume(chunk(1, "a", true));
    }

    private RuntimeRawStreamLogProcessor processor(RuntimeRawStreamLogRepository repository,
                                                   RuntimeRawStreamLogProperties properties) {
        return new RuntimeRawStreamLogProcessor(properties, repository, new TestIdGenerator());
    }

    private RuntimeRawStreamLogProperties properties(int maxChars, int hardMaxChars) {
        RuntimeRawStreamLogProperties properties = new RuntimeRawStreamLogProperties();
        properties.setEnabled(true);
        properties.setTransport("enterprise-mq");
        properties.setCoalesceWindow(Duration.ofMillis(100));
        properties.setMaxChars(maxChars);
        properties.setHardMaxChars(hardMaxChars);
        properties.setMaxRowsPerRun(100);
        return properties;
    }

    private AgentRuntimeRequest request() {
        return new AgentRuntimeRequest(
                "tenant1",
                "user1",
                "session1",
                "run1",
                null,
                "hello",
                List.of(),
                MemoryContext.empty(),
                null,
                null,
                Map.of(),
                RuntimeForwardHeaders.empty()
        );
    }

    private RuntimeRawStreamChunk chunk(long index, String content, boolean terminal) {
        return new RuntimeRawStreamChunk(
                "tenant1",
                "user1",
                "session1",
                "run1",
                "relay",
                "relay-stream-http",
                index,
                content,
                content.length(),
                false,
                terminal,
                java.time.Instant.now()
        );
    }

    private static class InMemoryPublisher implements RuntimeRawStreamLogPublisher {
        private final List<RuntimeRawStreamChunk> chunks = new CopyOnWriteArrayList<>();

        @Override
        public void publish(RuntimeRawStreamChunk chunk) {
            chunks.add(chunk);
        }

        List<RuntimeRawStreamChunk> chunks() {
            return chunks;
        }
    }

    private static class InMemoryRepository implements RuntimeRawStreamLogRepository {
        private final List<RuntimeRawStreamLogEntry> entries = new CopyOnWriteArrayList<>();

        @Override
        public void save(RuntimeRawStreamLogEntry entry) {
            entries.add(entry);
        }

        List<RuntimeRawStreamLogEntry> entries() {
            return entries;
        }
    }

    private static class FailingRepository implements RuntimeRawStreamLogRepository {
        @Override
        public void save(RuntimeRawStreamLogEntry entry) {
            throw new IllegalStateException("raw log db down");
        }
    }

    private static class TestIdGenerator implements IdGenerator {
        private int sequence;

        @Override
        public String newId(String prefix, IdGenerateContext context) {
            return prefix + "_" + (++sequence);
        }
    }
}
