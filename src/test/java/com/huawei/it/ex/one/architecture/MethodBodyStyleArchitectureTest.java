/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

/** Parse source without annotation processing or application class loading. */
class MethodBodyStyleArchitectureTest {
    @Test
    void productionMethodsAndLogicalExpressionsStayWithinLimits() throws Exception {
        Path root = Path.of(System.getProperty("user.dir"), "src/main/java");
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("A JDK is required for the source guard").isNotNull();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        List<String> violations = new ArrayList<>();
        try (var paths = Files.walk(root); var files = compiler.getStandardFileManager(diagnostics, null, null)) {
            var inputs = files.getJavaFileObjectsFromPaths(paths.filter(p -> p.toString().endsWith(".java")).toList());
            var task = (JavacTask) compiler.getTask(null, files, diagnostics,
                    List.of("-proc:none", "--release", "21"), null, inputs);
            var units = task.parse();
            var positions = Trees.instance(task).getSourcePositions();
            for (var unit : units) {
                String source = unit.getSourceFile().getCharContent(true).toString();
                String stripped = withoutComments(source);
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitMethod(MethodTree method, Void unused) {
                        if (method.getBody() != null && method.getReturnType() != null) {
                            int start = (int) positions.getStartPosition(unit, method);
                            int end = (int) positions.getEndPosition(unit, method);
                            long lines = stripped.substring(start, end).lines().filter(s -> !s.isBlank()).count();
                            if (lines > 50) {
                                report(method, "method " + method.getName() + ": " + lines + " lines > 50");
                            }
                            if (method.getModifiers().getFlags().contains(Modifier.PRIVATE)
                                    && method.getParameters().size() > 5) {
                                report(method, "private method " + method.getName() + ": parameters > 5");
                            }
                        }
                        return super.visitMethod(method, unused);
                    }

                    @Override
                    public Void visitBinary(BinaryTree binary, Void unused) {
                        if (logical(binary) && !logicalParent(getCurrentPath())) {
                            int count = logicalCount(binary);
                            if (count > 5) {
                                report(binary, "logical operators: " + count + " > 5");
                            }
                        }
                        return super.visitBinary(binary, unused);
                    }

                    private void report(Tree tree, String detail) {
                        long line = unit.getLineMap().getLineNumber(positions.getStartPosition(unit, tree));
                        violations.add(root.relativize(Path.of(unit.getSourceFile().toUri())) + ":" + line + " " + detail);
                    }
                }.scan(unit, null);
            }
        }
        assertThat(diagnostics.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).toList())
                .as("Source parse errors").isEmpty();
        assertThat(violations).as("Method body style violations:%n%s", String.join("\n", violations)).isEmpty();
    }

    @Test
    void commentCountingPreservesStringsAndTextBlocks() {
        String source = "String url = \"https://example.com/*literal*/\"; // comment\n"
                + "/* block\ncomment */ int value = 1;\n"
                + "String text = \"\"\"\n// literal\n/* literal */\n\"\"\";\n"
                + "char quote = '\\''; // tail";
        String stripped = withoutComments(source);
        assertThat(stripped).hasSize(source.length()).contains("https://example.com/*literal*/", "// literal", "/* literal */");
        assertThat(stripped).doesNotContain("// comment", "block", "tail");
        assertThat(stripped.lines().filter(line -> !line.isBlank()).count()).isEqualTo(7);
    }

    private static boolean logical(Tree tree) {
        return tree.getKind() == Tree.Kind.CONDITIONAL_AND || tree.getKind() == Tree.Kind.CONDITIONAL_OR;
    }

    private static boolean logicalParent(TreePath path) {
        TreePath parent = path.getParentPath();
        while (parent != null && parent.getLeaf().getKind() == Tree.Kind.PARENTHESIZED) {
            parent = parent.getParentPath();
        }
        return parent != null && logical(parent.getLeaf());
    }

    private static int logicalCount(Tree tree) {
        return new TreeScanner<Integer, Void>() {
            @Override
            public Integer visitBinary(BinaryTree node, Void unused) {
                return reduce(logical(node) ? 1 : 0, super.visitBinary(node, unused));
            }

            @Override
            public Integer visitLambdaExpression(LambdaExpressionTree node, Void unused) {
                return 0;
            }

            @Override
            public Integer visitClass(ClassTree node, Void unused) {
                return 0;
            }

            @Override
            public Integer reduce(Integer left, Integer right) {
                return (left == null ? 0 : left) + (right == null ? 0 : right);
            }
        }.scan(tree, null);
    }

    private static String withoutComments(String source) {
        StringBuilder result = new StringBuilder(source);
        int mode = 0; // 0: code, 1: line comment, 2: block comment, 3: string, 4: char, 5: text block.
        for (int i = 0; i < source.length(); i++) {
            char current = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (mode == 1 || mode == 2) {
                if (mode == 1 && current == '\n') {
                    mode = 0;
                } else if (mode == 2 && current == '*' && next == '/') {
                    result.setCharAt(i, ' ');
                    result.setCharAt(++i, ' ');
                    mode = 0;
                } else if (current != '\n' && current != '\r') {
                    result.setCharAt(i, ' ');
                }
            } else if (mode == 3 || mode == 4 || mode == 5) {
                if (current == '\\') {
                    i++;
                } else if (mode == 5 && source.startsWith("\"\"\"", i)) {
                    mode = 0;
                    i += 2;
                } else if (mode == 3 && current == '"' || mode == 4 && current == '\'') {
                    mode = 0;
                }
            } else if (current == '/' && (next == '/' || next == '*')) {
                mode = next == '/' ? 1 : 2;
                result.setCharAt(i, ' ');
                result.setCharAt(++i, ' ');
            } else if (source.startsWith("\"\"\"", i)) {
                mode = 5;
                i += 2;
            } else if (current == '"' || current == '\'') {
                mode = current == '"' ? 3 : 4;
            }
        }
        return result.toString();
    }
}
