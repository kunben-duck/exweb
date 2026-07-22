package com.huawei.it.ex.one.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.module.ModuleFinder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enterprise import order guard for production and test Java sources.
 */
class ImportOrderArchitectureTest {
    private static final Pattern IMPORT_LINE = Pattern.compile("^import (static )?([^;\\s]+);$");
    private static final List<SystemPackage> SYSTEM_PACKAGES = systemPackages();

    @Test
    void allJavaSourcesUseEnterpriseImportOrder() throws IOException {
        Path projectRoot = Path.of(System.getProperty("user.dir"));
        List<String> violations = new ArrayList<>();

        inspectSourceRoot(projectRoot, projectRoot.resolve("src/main/java"), violations);
        inspectSourceRoot(projectRoot, projectRoot.resolve("src/test/java"), violations);

        assertThat(violations)
                .as("Java import blocks must follow the enterprise order:%n%s", String.join("%n", violations))
                .isEmpty();
    }

    @Test
    void classifiesAllEnterpriseImportGroups() {
        assertThat(categoryOf("android.view.View")).isEqualTo(ImportCategory.ANDROID);
        assertThat(categoryOf("androidx.annotation.Nullable")).isEqualTo(ImportCategory.ANDROID);
        assertThat(categoryOf("com.android.tools.Tool")).isEqualTo(ImportCategory.ANDROID);
        assertThat(categoryOf("com.hisilicon.platform.Service")).isEqualTo(ImportCategory.HUAWEI);
        assertThat(categoryOf("com.huawei.it.Service")).isEqualTo(ImportCategory.HUAWEI);
        assertThat(categoryOf("com.huaweicloud.obs.Client")).isEqualTo(ImportCategory.OTHER_COMMERCIAL);
        assertThat(categoryOf("reactor.core.publisher.Mono")).isEqualTo(ImportCategory.OTHER_THIRD_PARTY);
        assertThat(categoryOf("org.junit.jupiter.api.Test")).isEqualTo(ImportCategory.NET_ORG);
        assertThat(categoryOf("javacard.framework.Applet")).isEqualTo(ImportCategory.JAVACARD);
        assertThat(categoryOf("java.io.IOException")).isEqualTo(ImportCategory.JAVA_BASE);
        assertThat(categoryOf("java.sql.Connection")).isEqualTo(ImportCategory.JAVA_OTHER);
        assertThat(categoryOf("javax.crypto.Cipher")).isEqualTo(ImportCategory.JAVA_BASE);
        assertThat(categoryOf("javax.swing.JPanel")).isEqualTo(ImportCategory.JAVA_EXTENSION);
    }

    @Test
    void ordersStaticAndRegularImportsWithRequiredSeparators() {
        List<ImportEntry> imports = List.of(
                entry("import java.sql.Connection;"),
                entry("import static org.mockito.Mockito.mock;"),
                entry("import com.fasterxml.jackson.databind.JsonNode;"),
                entry("import static org.assertj.core.api.Assertions.assertThat;"),
                entry("import javax.swing.JPanel;"),
                entry("import java.io.IOException;"),
                entry("import org.junit.jupiter.api.Test;"),
                entry("import com.huawei.it.ex.one.common.trace.TraceContext;"));

        assertThat(expectedImportBlock(imports)).containsExactly(
                "import static org.assertj.core.api.Assertions.assertThat;",
                "import static org.mockito.Mockito.mock;",
                "",
                "import com.huawei.it.ex.one.common.trace.TraceContext;",
                "",
                "import com.fasterxml.jackson.databind.JsonNode;",
                "",
                "import org.junit.jupiter.api.Test;",
                "",
                "import java.io.IOException;",
                "",
                "import java.sql.Connection;",
                "",
                "import javax.swing.JPanel;");
    }

    private static void inspectSourceRoot(Path projectRoot, Path sourceRoot, List<String> violations)
            throws IOException {
        try (var files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                inspectFile(projectRoot, file, violations);
            }
        }
    }

    private static void inspectFile(Path projectRoot, Path file, List<String> violations) throws IOException {
        List<String> lines = Files.readAllLines(file);
        int firstImport = firstImportLine(lines);
        if (firstImport < 0) {
            return;
        }
        int lastImport = lastImportLine(lines);
        List<String> actual = List.copyOf(lines.subList(firstImport, lastImport + 1));
        try {
            List<ImportEntry> imports = actual.stream()
                    .filter(line -> !line.isBlank())
                    .map(ImportOrderArchitectureTest::entry)
                    .toList();
            List<String> expected = expectedImportBlock(imports);
            if (!actual.equals(expected)) {
                violations.add(violation(projectRoot, file, firstImport, actual, expected));
            }
        } catch (IllegalArgumentException ex) {
            violations.add(projectRoot.relativize(file) + ":" + (firstImport + 1) + " " + ex.getMessage());
        }
    }

    private static int firstImportLine(List<String> lines) {
        for (int index = 0; index < lines.size(); index++) {
            if (isImportLine(lines.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private static int lastImportLine(List<String> lines) {
        for (int index = lines.size() - 1; index >= 0; index--) {
            if (isImportLine(lines.get(index))) {
                return index;
            }
        }
        throw new IllegalArgumentException("Import block has no import statement");
    }

    private static boolean isImportLine(String line) {
        return line.stripLeading().startsWith("import ");
    }

    private static ImportEntry entry(String line) {
        Matcher matcher = IMPORT_LINE.matcher(line);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid import block line: " + line);
        }
        return new ImportEntry(matcher.group(1) != null, matcher.group(2), categoryOf(matcher.group(2)));
    }

    private static List<String> expectedImportBlock(List<ImportEntry> imports) {
        List<String> result = new ArrayList<>();
        appendSection(result, imports.stream().filter(ImportEntry::staticImport).toList());
        List<ImportEntry> regularImports = imports.stream().filter(entry -> !entry.staticImport()).toList();
        if (!result.isEmpty() && !regularImports.isEmpty()) {
            result.add("");
        }
        appendSection(result, regularImports);
        return List.copyOf(result);
    }

    private static void appendSection(List<String> result, List<ImportEntry> entries) {
        Map<ImportCategory, List<ImportEntry>> grouped = new EnumMap<>(ImportCategory.class);
        entries.forEach(entry -> grouped.computeIfAbsent(entry.category(), ignored -> new ArrayList<>()).add(entry));
        boolean firstGroup = true;
        for (ImportCategory category : ImportCategory.values()) {
            List<ImportEntry> group = grouped.getOrDefault(category, List.of()).stream()
                    .sorted(Comparator.comparing(ImportEntry::target))
                    .toList();
            if (group.isEmpty()) {
                continue;
            }
            if (!firstGroup) {
                result.add("");
            }
            group.stream().map(ImportEntry::statement).forEach(result::add);
            firstGroup = false;
        }
    }

    private static ImportCategory categoryOf(String target) {
        if (hasPrefix(target, "android") || hasPrefix(target, "androidx") || hasPrefix(target, "com.android")) {
            return ImportCategory.ANDROID;
        }
        if (hasPrefix(target, "com.hisilicon") || hasPrefix(target, "com.huawei")) {
            return ImportCategory.HUAWEI;
        }
        if (hasPrefix(target, "com")) {
            return ImportCategory.OTHER_COMMERCIAL;
        }
        if (hasPrefix(target, "net") || hasPrefix(target, "org")) {
            return ImportCategory.NET_ORG;
        }
        if (hasPrefix(target, "javacard")) {
            return ImportCategory.JAVACARD;
        }
        String moduleName = systemModuleName(target);
        if ("java.base".equals(moduleName)) {
            return ImportCategory.JAVA_BASE;
        }
        if (hasPrefix(target, "java")) {
            return ImportCategory.JAVA_OTHER;
        }
        if (hasPrefix(target, "javax")) {
            return ImportCategory.JAVA_EXTENSION;
        }
        return ImportCategory.OTHER_THIRD_PARTY;
    }

    private static String systemModuleName(String target) {
        return SYSTEM_PACKAGES.stream()
                .filter(systemPackage -> hasPrefix(target, systemPackage.name()))
                .findFirst()
                .map(SystemPackage::moduleName)
                .orElse(null);
    }

    private static boolean hasPrefix(String target, String prefix) {
        return target.equals(prefix) || target.startsWith(prefix + ".");
    }

    private static List<SystemPackage> systemPackages() {
        List<SystemPackage> packages = new ArrayList<>();
        ModuleFinder.ofSystem().findAll().forEach(module -> module.descriptor().packages()
                .forEach(packageName -> packages.add(new SystemPackage(packageName, module.descriptor().name()))));
        return packages.stream()
                .sorted(Comparator.comparingInt((SystemPackage value) -> value.name().length()).reversed())
                .toList();
    }

    private static String violation(Path projectRoot, Path file, int firstImport,
                                    List<String> actual, List<String> expected) {
        return projectRoot.relativize(file) + ":" + (firstImport + 1)
                + " expected=" + expected + " actual=" + actual;
    }

    private enum ImportCategory {
        ANDROID,
        HUAWEI,
        OTHER_COMMERCIAL,
        OTHER_THIRD_PARTY,
        NET_ORG,
        JAVACARD,
        JAVA_BASE,
        JAVA_OTHER,
        JAVA_EXTENSION
    }

    private record ImportEntry(boolean staticImport, String target, ImportCategory category) {
        private String statement() {
            return "import " + (staticImport ? "static " : "") + target + ";";
        }
    }

    private record SystemPackage(String name, String moduleName) {
    }
}
