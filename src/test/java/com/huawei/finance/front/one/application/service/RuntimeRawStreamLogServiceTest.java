package com.huawei.finance.front.one.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.front.one.application.config.RuntimeRawStreamLogProperties;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.finance.front.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.finance.front.one.application.integration.conversation.RuntimeRawStreamLogRepository;
import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
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
    void coalescesRawChunksBeforePersisting() {
        InMemoryRepository repository = new InMemoryRepository();
        RuntimeRawStreamLogService service = service(repository, properties(100, 4096));

        StepVerifier.create(service.capture(Flux.just("a", "b"), request(), "relay", "relay-stream-http"))
                .expectNext("a", "b")
                .verifyComplete();

        List<RuntimeRawStreamLogEntry> entries = awaitEntries(repository, 1);
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().rawContent()).isEqualTo("ab");
        assertThat(entries.getFirst().chunkCount()).isEqualTo(2);
        assertThat(entries.getFirst().truncated()).isFalse();
    }

    @Test
    void splitsSingleOversizedChunkWithoutMarkingTruncated() {
        InMemoryRepository repository = new InMemoryRepository();
        RuntimeRawStreamLogService service = service(repository, properties(4, 100));

        StepVerifier.create(service.capture(Flux.just("abcdefghij"), request(), "relay", "relay-stream-http"))
                .expectNext("abcdefghij")
                .verifyComplete();

        List<RuntimeRawStreamLogEntry> entries = awaitEntries(repository, 3);
        assertThat(entries).extracting(RuntimeRawStreamLogEntry::rawContent)
                .containsExactly("abcd", "efgh", "ij");
        assertThat(entries).allMatch(entry -> !entry.truncated());
        assertThat(entries).extracting(RuntimeRawStreamLogEntry::splitPartCount)
                .containsExactly(3, 3, 3);
    }

    @Test
    void marksTerminalChunkAndRedactsSensitiveFields() {
        InMemoryRepository repository = new InMemoryRepository();
        RuntimeRawStreamLogService service = service(repository, properties(4096, 4096));

        StepVerifier.create(service.capture(Flux.just("{\"token\":\"secret\",\"safe\":\"yes\"}", "steam-complete"),
                        request(), "relay", "relay-stream-http"))
                .expectNextCount(2)
                .verifyComplete();

        List<RuntimeRawStreamLogEntry> entries = awaitEntries(repository, 1);
        assertThat(entries.getFirst().rawContent())
                .contains("[REDACTED]")
                .doesNotContain("secret");
        assertThat(entries.getFirst().terminal()).isTrue();
        assertThat(entries.getFirst().truncated()).isFalse();
    }

    @Test
    void marksHardLimitDiscardAsTruncated() {
        InMemoryRepository repository = new InMemoryRepository();
        RuntimeRawStreamLogService service = service(repository, properties(10, 20));

        StepVerifier.create(service.capture(Flux.just("abcdefghijklmnopqrstuvwxyz1234"), request(), "relay", "relay-stream-http"))
                .expectNext("abcdefghijklmnopqrstuvwxyz1234")
                .verifyComplete();

        List<RuntimeRawStreamLogEntry> entries = awaitEntries(repository, 2);
        assertThat(entries).hasSize(2);
        assertThat(entries.stream().map(RuntimeRawStreamLogEntry::rawContent).reduce("", String::concat))
                .contains("[TRUNCATED]");
        assertThat(entries).anyMatch(RuntimeRawStreamLogEntry::truncated);
        assertThat(entries).extracting(RuntimeRawStreamLogEntry::sourceContentLength)
                .containsOnly(30);
    }

    @Test
    void rawLogEntryCreationFailureDoesNotAffectMainStream() {
        RuntimeRawStreamLogService service = new RuntimeRawStreamLogService(
                properties(100, 4096), new InMemoryRepository(), new FailingIdGenerator());

        StepVerifier.create(service.capture(Flux.just("a", "b"), request(), "relay", "relay-stream-http"))
                .expectNext("a", "b")
                .verifyComplete();
    }

    @Test
    void rawLogRepositoryFailureDoesNotAffectMainStream() {
        RuntimeRawStreamLogService service = service(new FailingRepository(), properties(100, 4096));

        StepVerifier.create(service.capture(Flux.just("a", "b"), request(), "relay", "relay-stream-http"))
                .expectNext("a", "b")
                .verifyComplete();
    }

    private RuntimeRawStreamLogService service(RuntimeRawStreamLogRepository repository,
                                               RuntimeRawStreamLogProperties properties) {
        return new RuntimeRawStreamLogService(properties, repository, new TestIdGenerator());
    }

    private RuntimeRawStreamLogProperties properties(int maxChars, int hardMaxChars) {
        RuntimeRawStreamLogProperties properties = new RuntimeRawStreamLogProperties();
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

    private List<RuntimeRawStreamLogEntry> awaitEntries(InMemoryRepository repository, int expected) {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline && repository.entries().size() < expected) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return repository.entries();
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

    private static class FailingIdGenerator implements IdGenerator {
        @Override
        public String newId(String prefix, IdGenerateContext context) {
            throw new IllegalStateException("id service down");
        }
    }
}
