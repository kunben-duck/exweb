/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 方法签名复杂度护栏。
 *
 * <p>本测试只约束普通 Java 方法声明。DTO/domain/command record 的字段数量不属于方法签名，
 * Spring HTTP 入口方法和 JDK/Spring/MyBatis override 方法也按框架要求排除。</p>
 */
class MethodSignatureParameterCountTest {
    private static final int MAX_PARAMETER_COUNT = 5;
    private static final Pattern METHOD_DECLARATION = Pattern.compile(
            "(?ms)^\\s*(?<annotations>(?:@[\\w.]+(?:\\([^)]*\\))?\\s*)*)"
                    + "(?<modifiers>(?:(?:public|protected|private|static|final|synchronized|default|abstract|native|strictfp)\\s+)+)"
                    + "(?!class\\b|interface\\b|enum\\b|record\\b)"
                    + "(?<returnType>[\\w<>\\[\\].?,\\s]+?)\\s+"
                    + "(?<methodName>\\w+)\\s*\\((?<parameters>.*?)\\)\\s*"
                    + "(?:throws [^{;]+)?[\\{;]");
    private static final Pattern SPRING_MAPPING = Pattern.compile(
            "@(?:Get|Post|Put|Delete|Patch|Request)Mapping\\b");

    @Test
    void ordinaryMethodSignaturesShouldNotHaveMoreThanFiveParameters() throws IOException {
        Path sourceRoot = Path.of(System.getProperty("user.dir"), "src/main/java");
        List<String> violations = new ArrayList<>();
        try (var files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                collectViolations(sourceRoot, file, violations);
            }
        }
        assertThat(violations)
                .as("普通方法入参不应超过 %s 个:%n%s", MAX_PARAMETER_COUNT, String.join(System.lineSeparator(), violations))
                .isEmpty();
    }

    private void collectViolations(Path sourceRoot, Path file, List<String> violations) throws IOException {
        String source = removeComments(Files.readString(file));
        String className = file.getFileName().toString().replace(".java", "");
        Matcher matcher = METHOD_DECLARATION.matcher(source);
        while (matcher.find()) {
            String methodName = matcher.group("methodName");
            if (methodName.equals(className) || isExcludedFrameworkMethod(matcher.group("annotations"))) {
                continue;
            }
            int parameterCount = countParameters(matcher.group("parameters"));
            if (parameterCount > MAX_PARAMETER_COUNT) {
                violations.add(sourceRoot.relativize(file) + "#" + methodName + "(" + parameterCount + ")");
            }
        }
    }

    private boolean isExcludedFrameworkMethod(String annotations) {
        if (annotations == null || annotations.isBlank()) {
            return false;
        }
        return annotations.contains("@Override") || SPRING_MAPPING.matcher(annotations).find();
    }

    private int countParameters(String parameters) {
        if (parameters == null || parameters.isBlank()) {
            return 0;
        }
        List<String> parts = splitTopLevel(parameters);
        return (int) parts.stream().filter(part -> !part.isBlank()).count();
    }

    private List<String> splitTopLevel(String value) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch == '<' || ch == '(' || ch == '[' || ch == '{') {
                depth++;
            } else if (ch == '>' || ch == ')' || ch == ']' || ch == '}') {
                depth = Math.max(0, depth - 1);
            }
            if (ch == ',' && depth == 0) {
                parts.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        parts.add(current.toString().trim());
        return parts;
    }

    private String removeComments(String source) {
        String withoutBlockComments = source.replaceAll("(?s)/\\*.*?\\*/", "");
        return withoutBlockComments.replaceAll("(?m)//.*$", "");
    }
}
