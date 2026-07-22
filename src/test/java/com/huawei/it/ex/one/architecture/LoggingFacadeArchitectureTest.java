package com.huawei.it.ex.one.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Logging dependency guard: production code must use the application facade.
 */
class LoggingFacadeArchitectureTest {
    private static final Path LOGGING_PACKAGE = Path.of("com/huawei/it/ex/one/common/logging");
    private static final Pattern LOG_CALL = Pattern.compile(
            "\\blog\\.(?:trace|debug|info|warn|error)\\s*\\(");
    private static final Pattern HAN_CHARACTER = Pattern.compile("\\p{IsHan}");

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

    @Test
    void productionLogStatementsMustUseEnglishTemplates() throws IOException {
        Path sourceRoot = Path.of(System.getProperty("user.dir"), "src/main/java");
        List<String> violations = new ArrayList<>();
        try (var files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                var matcher = LOG_CALL.matcher(source);
                while (matcher.find()) {
                    int statementEnd = source.indexOf(';', matcher.end());
                    if (statementEnd < 0) {
                        continue;
                    }
                    String statement = source.substring(matcher.start(), statementEnd + 1);
                    if (HAN_CHARACTER.matcher(statement).find()) {
                        Path relative = sourceRoot.relativize(file);
                        violations.add(relative + ":" + lineNumber(source, matcher.start()));
                    }
                }
            }
        }

        assertThat(violations)
                .as("Production log statements must use English templates. Violations: %s", violations)
                .isEmpty();
    }

    private int lineNumber(String source, int offset) {
        int line = 1;
        for (int index = 0; index < offset; index++) {
            if (source.charAt(index) == '\n') {
                line++;
            }
        }
        return line;
    }
}
