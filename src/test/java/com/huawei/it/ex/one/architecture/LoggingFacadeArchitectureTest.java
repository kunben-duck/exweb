package com.huawei.it.ex.one.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Logging dependency guard: production code must use the application facade.
 */
class LoggingFacadeArchitectureTest {
    private static final Path LOGGING_PACKAGE = Path.of("com/huawei/it/ex/one/common/logging");

    @Test
    void productionCodeOutsideLoggingAdapterMustNotDependOnSlf4j() throws IOException {
        Path sourceRoot = Path.of(System.getProperty("user.dir"), "src/main/java");
        List<String> violations = new ArrayList<>();
        try (var files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Path relative = sourceRoot.relativize(file);
                if (relative.startsWith(LOGGING_PACKAGE)) {
                    continue;
                }
                if (Files.readString(file).contains("org.slf4j.")) {
                    violations.add(relative.toString());
                }
            }
        }

        assertThat(violations)
                .as("生产代码只能通过 AppLogger/AppLoggerFactory 使用日志，违规文件: %s", violations)
                .isEmpty();
    }
}
