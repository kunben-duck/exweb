package com.huawei.it.ex.one;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ArchitectureNamingTest {
    @Test
    void schemaTablesUseFinExNaming() throws Exception {
        String schema = Files.readString(Path.of("src/main/resources/db/init-20260718.sql"));
        Matcher matcher = Pattern.compile("CREATE TABLE IF NOT EXISTS\\s+([a-zA-Z0-9_]+)").matcher(schema);
        int count = 0;
        while (matcher.find()) {
            count++;
            assertThat(matcher.group(1)).matches("^fin_ex_.*_t$");
        }
        assertThat(count).isGreaterThan(0);
    }

    @Test
    void sessionSchemaDeclaresAppTagColumnsAndListIndex() throws Exception {
        String schema = Files.readString(Path.of("src/main/resources/db/init-20260718.sql"));

        assertThat(schema)
                .contains("app_id VARCHAR(128)")
                .contains("app_name VARCHAR(256)")
                .contains("idx_fin_ex_chat_session_owner_app_updated")
                .contains("tenant_id, user_id, app_id, updated_at, id");
    }

    @Test
    void redisPrefixesUseFinExNaming() throws Exception {
        String application = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(application).contains("mode: ${FINANCEEX_REDIS_MODE:}");
        assertThat(application).contains("nodes: ${FINANCEEX_REDIS_CLUSTER_NODES:}");
        assertThat(application).contains("redis-key-prefix: fin_ex:runtime_binding");
        assertThat(application).contains("active-key-prefix: fin_ex:chat_run:active");
        assertThat(application).contains("cancel-key-prefix: fin_ex:chat_run:cancel");
        assertThat(application).contains("recover-lock-key-prefix: fin_ex:chat_run:recover_lock");
        assertThat(application).contains("redis-channel-prefix: fin_ex:chat_stream");
        assertThat(application).contains("redis-key-prefix: fin_ex:memory:short_term");
    }

    @Test
    void runtimeBindingCacheDoesNotUseRedisKeysCommand() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/huawei/it/ex/one/infrastructure/runtime/RedisRuntimeBindingCache.java"
        ));

        assertThat(source)
                .contains("opsForSet().members")
                .doesNotContain(".keys(");
    }

    @Test
    void relayRuntimeConfigurationDoesNotExposeRemovedProtocolKeys() throws Exception {
        String application = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(application)
                .doesNotContain("FINANCEEX_AGENT_RUNTIME_PROTOCOL")
                .doesNotContain("FINANCEEX_RELAY_AGENT_API_ADAPTER")
                .doesNotContain("FINANCEEX_RELAY_AGENT_WEBSOCKET_URL")
                .doesNotContain("FINANCEEX_RELAY_AGENT_WEBSOCKET_PATH")
                .doesNotContain("FINANCEEX_RELAY_AGENT_BASE_URL")
                .doesNotContain("FINANCEEX_RELAY_AGENT_STREAM_PATH")
                .doesNotContain("FINANCEEX_RELAY_AGENT_STOP_PATH")
                .doesNotContain("FINANCEEX_RELAY_AGENT_CANCEL_SUPPORTED")
                .doesNotContain("FINANCEEX_RELAY_ADAPTER")
                .doesNotContain("FINANCEEX_RELAY_MAX_IN_MEMORY_SIZE")
                .doesNotContain("FINANCEEX_AGENT_RUNTIME_FORWARD_COOKIE_ALLOWED_ADAPTERS")
                .doesNotContain("http-streamable")
                .doesNotContain("relay-http-streamable")
                .doesNotContain("websocket-url");
    }

    @Test
    void applicationServicesDoNotResolveAuthContextDirectly() throws Exception {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java/com/huawei/it/ex/one/application/service"))) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> {
                        try {
                            String source = Files.readString(path);
                            assertThat(source)
                                    .as(path + " must receive UserContext from interface entrypoints")
                                    .doesNotContain("AuthContextProvider")
                                    .doesNotContain("auth.resolve()");
                        } catch (Exception ex) {
                            throw new IllegalStateException(ex);
                        }
                    });
        }
    }

    @Test
    void infrastructurePackagesDoNotKeepRedundantTechnologySubpackages() throws Exception {
        String sourceRoot = "src/main/java/com/huawei/it/ex/one/infrastructure";
        try (Stream<Path> files = Files.walk(Path.of(sourceRoot))) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> {
                        String normalized = path.toString().replace('\\', '/');
                        assertThat(normalized)
                                .as("infrastructure implementation packages should stay business-oriented: " + path)
                                .doesNotContain("/mybatis/")
                                .doesNotContain("/runtime/binding/")
                                .doesNotContain("/session/persistence/")
                                .doesNotContain("/agent/runtime/");
                    });
        }
    }
}
