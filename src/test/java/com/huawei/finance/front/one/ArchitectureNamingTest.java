package com.huawei.finance.front.one;

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
        String schema = Files.readString(Path.of("src/main/resources/db/schema.sql"));
        Matcher matcher = Pattern.compile("CREATE TABLE IF NOT EXISTS\\s+([a-zA-Z0-9_]+)").matcher(schema);
        int count = 0;
        while (matcher.find()) {
            count++;
            assertThat(matcher.group(1)).matches("^fin_ex_.*_t$");
        }
        assertThat(count).isGreaterThan(0);
    }

    @Test
    void redisPrefixesUseFinExNaming() throws Exception {
        String application = Files.readString(Path.of("src/main/resources/application.yml"));

        assertThat(application).contains("redis-key-prefix: fin_ex:runtime_binding");
        assertThat(application).contains("active-key-prefix: fin_ex:chat_run:active");
        assertThat(application).contains("cancel-key-prefix: fin_ex:chat_run:cancel");
        assertThat(application).contains("redis-channel-prefix: fin_ex:chat_stream");
        assertThat(application).contains("key-prefix: fin_ex:memory:short_term");
        assertThat(application).contains("key-prefix: fin_ex:memory:working");
    }

    @Test
    void applicationServicesDoNotResolveAuthContextDirectly() throws Exception {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java/com/huawei/finance/front/one/application/service"))) {
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
}
