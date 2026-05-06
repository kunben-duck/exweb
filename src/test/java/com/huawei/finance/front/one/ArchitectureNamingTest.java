package com.huawei.finance.front.one;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
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

        assertThat(application).contains("redis-key-prefix: fin_ex:agent_binding");
        assertThat(application).contains("active-key-prefix: fin_ex:task:active");
        assertThat(application).contains("card-key-prefix: fin_ex:task:card");
        assertThat(application).contains("key-prefix: fin_ex:memory:short_term");
        assertThat(application).contains("key-prefix: fin_ex:memory:working");
    }
}
