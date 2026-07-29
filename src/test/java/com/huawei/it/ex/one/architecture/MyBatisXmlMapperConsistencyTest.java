package com.huawei.it.ex.one.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MyBatis SQL 管理方式护栏。
 *
 * <p>项目约定 SQL 只维护在 resources/mapper 下的 XML 文件中，Java Mapper 接口只保留方法签名。
 * 该测试避免后续维护时重新引入注解 SQL，或新增 XML statement 后忘记同步接口方法。</p>
 */
class MyBatisXmlMapperConsistencyTest {
    private static final Path MAIN_SOURCE_ROOT = Path.of("src/main/java");
    private static final Path MAPPER_XML_ROOT = Path.of("src/main/resources/mapper");
    private static final String ACTIVE_DIALECT_MAPPER_SUFFIX = ".opengauss.xml";
    private static final Pattern NAMESPACE = Pattern.compile("<mapper\\s+namespace=\"([^\"]+)\"");
    private static final Pattern STATEMENT_ID = Pattern.compile("<(?:select|insert|update|delete)\\s+id=\"([^\"]+)\"");
    private static final Pattern STATEMENT_LINE = Pattern.compile("^\\s*<(select|insert|update|delete)\\s+id=\"([^\"]+)\".*");
    private static final Pattern SQL_ANNOTATION = Pattern.compile("@(?:Select|Insert|Update|Delete|Results|ResultMap|Result)\\b");
    private static final Pattern SELECT_WILDCARD_PROJECTION = Pattern.compile(
            "(?is)\\bSELECT\\s+(?:[A-Za-z_][A-Za-z0-9_]*\\.)?\\*");
    private static final Pattern INSERT_SELECT = Pattern.compile(
            "(?is)\\bINSERT\\s+INTO\\b.*\\bSELECT\\b");
    private static final Pattern STATEMENT_BLOCK = Pattern.compile(
            "(?is)<(select|insert|update|delete)\\s+id=\"([^\"]+)\"[^>]*>(.*?)</\\1>");
    private static final Pattern PREDICATE_CLAUSE = Pattern.compile(
            "(?is)\\b(?:WHERE|ON|HAVING)\\b(.*?)(?=\\b(?:WHERE|ON|HAVING|GROUP\\s+BY|ORDER\\s+BY|"
                    + "LIMIT|OFFSET|RETURNING|UNION(?:\\s+ALL)?|SET|VALUES)\\b|$)");
    private static final Pattern PREDICATE_COLUMN_FUNCTION = Pattern.compile(
            "(?i)\\b(?:LOWER|UPPER|TRIM|BTRIM|LTRIM|RTRIM|COALESCE|CAST|DATE_TRUNC|TO_CHAR|TO_DATE|"
                    + "SUBSTRING|LENGTH|ABS|ROUND|CEIL|FLOOR)\\s*\\(");
    private static final Pattern PREDICATE_COLUMN_ARITHMETIC = Pattern.compile(
            "(?i)(?:\\b(?:[A-Za-z_][A-Za-z0-9_]*\\.)?[A-Za-z_][A-Za-z0-9_]*\\b|\\?)\\s*"
                    + "(?:\\+|-|\\*|/|%)\\s*(?:\\b(?:[A-Za-z_][A-Za-z0-9_]*\\.)?"
                    + "[A-Za-z_][A-Za-z0-9_]*\\b|\\?|\\d+)");
    private static final Pattern XML_COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern XML_TAG = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern SQL_STRING_LITERAL = Pattern.compile("'(?:''|[^'])*'");
    private static final Pattern MYBATIS_PARAMETER = Pattern.compile("[#\\$]\\{[^}]+}");
    private static final Pattern MESSAGE_PART_TYPE_COLUMN = Pattern.compile(
            "(?m)^\\s*part_type\\s+VARCHAR\\((\\d+)\\)\\s+NOT NULL");
    private static final List<String> CHAT_MESSAGE_PART_TYPES = List.of(
            "ANSWER",
            "MESSAGE_SNAPSHOT",
            "PROGRESS",
            "METADATA",
            "AGENT",
            "THINKING",
            "TOOL",
            "REFERENCE",
            "CARD",
            "CLARIFICATION_REQUEST",
            "CLARIFICATION_RESPONSE",
            "AGENT_CLARIFICATION_REQUEST",
            "AGENT_CLARIFICATION_RESPONSE",
            "INTENT_CLARIFICATION_REQUEST",
            "INTENT_CLARIFICATION_RESPONSE",
            "DOMAIN_AGENT_REFUSAL",
            "ROUTE_SWITCH_CONFIRMATION_REQUEST",
            "ROUTE_SWITCH_CONFIRMATION_RESPONSE",
            "ROUTE_SWITCH_DECLINED",
            "RUNTIME_EVENT"
    );

    @Test
    void mapperXmlStatementsShouldMatchJavaMapperMethods() throws IOException {
        List<String> violations;
        try (var files = Files.walk(MAPPER_XML_ROOT)) {
            violations = files.filter(path -> path.toString().endsWith(ACTIVE_DIALECT_MAPPER_SUFFIX))
                    .flatMap(path -> validateXmlMapper(path).stream())
                    .toList();
        }

        assertThat(violations)
                .as("MyBatis XML namespace/id must match Java Mapper methods:%n%s",
                        String.join(System.lineSeparator(), violations))
                .isEmpty();
    }

    @Test
    void infrastructureMyBatisMappersShouldNotUseSqlAnnotations() throws IOException {
        Path infrastructureRoot = MAIN_SOURCE_ROOT.resolve("com/huawei/it/ex/one/infrastructure");
        List<String> violations;
        try (var files = Files.walk(infrastructureRoot)) {
            violations = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(this::containsForbiddenSqlAnnotation)
                    .map(MAIN_SOURCE_ROOT::relativize)
                    .map(Path::toString)
                    .toList();
        }

        assertThat(violations)
                .as("MyBatis SQL should be kept in XML, not Java annotations:%n%s",
                        String.join(System.lineSeparator(), violations))
                .isEmpty();
    }

    @Test
    void mapperSelectsShouldUseExplicitColumnLists() throws IOException {
        List<String> violations;
        try (var files = Files.walk(MAPPER_XML_ROOT)) {
            violations = files.filter(path -> path.toString().endsWith(ACTIVE_DIALECT_MAPPER_SUFFIX))
                    .filter(this::containsSelectWildcardProjection)
                    .map(MAPPER_XML_ROOT::relativize)
                    .map(Path::toString)
                    .toList();
        }

        assertThat(violations)
                .as("MyBatis SELECT projections must list columns explicitly:%n%s",
                        String.join(System.lineSeparator(), violations))
                .isEmpty();
    }

    @Test
    void mapperInsertsShouldUseValuesInsteadOfSelect() throws IOException {
        List<String> violations;
        try (var files = Files.walk(MAPPER_XML_ROOT)) {
            violations = files.filter(path -> path.toString().endsWith(ACTIVE_DIALECT_MAPPER_SUFFIX))
                    .flatMap(path -> findInsertSelectViolations(path).stream())
                    .toList();
        }

        assertThat(violations)
                .as("MyBatis INSERT statements must use VALUES instead of SELECT:%n%s",
                        String.join(System.lineSeparator(), violations))
                .isEmpty();
    }

    @Test
    void mapperPredicatesShouldNotTransformOrCalculateColumns() throws IOException {
        List<String> violations;
        try (var files = Files.walk(MAPPER_XML_ROOT)) {
            violations = files.filter(path -> path.toString().endsWith(ACTIVE_DIALECT_MAPPER_SUFFIX))
                    .flatMap(path -> findPredicateExpressionViolations(path).stream())
                    .toList();
        }

        assertThat(violations)
                .as("MyBatis WHERE/ON/HAVING clauses must not transform or calculate columns:%n%s",
                        String.join(System.lineSeparator(), violations))
                .isEmpty();
    }

    @Test
    void mapperXmlStatementsShouldHaveBusinessComments() throws IOException {
        List<String> violations;
        try (var files = Files.walk(MAPPER_XML_ROOT)) {
            violations = files.filter(path -> path.toString().endsWith(ACTIVE_DIALECT_MAPPER_SUFFIX))
                    .flatMap(path -> findStatementsWithoutBusinessComment(path).stream())
                    .toList();
        }

        assertThat(violations)
                .as("Every MyBatis statement should explain its database operation:%n%s",
                        String.join(System.lineSeparator(), violations))
                .isEmpty();
    }

    @Test
    void javaMapperMethodsShouldHaveJavadocs() throws IOException {
        List<Path> javaMapperFiles;
        try (var files = Files.walk(MAPPER_XML_ROOT)) {
            javaMapperFiles = files.filter(path -> path.toString().endsWith(ACTIVE_DIALECT_MAPPER_SUFFIX))
                    .map(this::javaMapperFileFromXml)
                    .toList();
        }

        List<String> violations = javaMapperFiles.stream()
                .flatMap(path -> findMapperMethodsWithoutJavadocs(path).stream())
                .toList();

        assertThat(violations)
                .as("Every Java Mapper method should document operation and parameters:%n%s",
                        String.join(System.lineSeparator(), violations))
                .isEmpty();
    }

    @Test
    void chatRunGenericUpdateShouldKeepCancellingMonotonic() throws IOException {
        Path mapper = MAPPER_XML_ROOT.resolve("persistence/ChatRunMapper.opengauss.xml");
        String xml = Files.readString(mapper);
        int start = xml.indexOf("<update id=\"updateExisting\"");
        int end = xml.indexOf("</update>", start);

        assertThat(start).isGreaterThanOrEqualTo(0);
        assertThat(end).isGreaterThan(start);
        String updateExisting = xml.substring(start, end);
        assertThat(updateExisting)
                .contains("status IN ('CANCELLING', 'COMPLETED', 'WAITING_USER', 'FAILED', 'CANCELLED')")
                .contains("THEN last_seq")
                .contains("THEN finished_at")
                .contains("THEN updated_at");
    }

    @Test
    void chatRunAdmissionAndEventTerminalGatesShouldBeDatabaseBacked() throws IOException {
        String schema = Files.readString(Path.of("src/main/resources/db/init-20260718.sql"));
        assertThat(schema)
                .contains("CREATE UNIQUE INDEX IF NOT EXISTS uk_fin_ex_chat_run_active_session")
                .contains("WHERE status IN ('RUNNING', 'CANCELLING')")
                .contains("CREATE INDEX IF NOT EXISTS idx_fin_ex_chat_run_init_reconcile")
                .contains("CREATE INDEX IF NOT EXISTS idx_fin_ex_chat_interaction_reconcile");

        String eventMapper = Files.readString(
                MAPPER_XML_ROOT.resolve("persistence/ChatEventMapper.opengauss.xml"));
        assertThat(eventMapper)
                .contains("<select id=\"findEventAppendContext\"")
                .contains("<select id=\"lockRunForEventAppend\"")
                .contains("FOR SHARE OF r NOWAIT")
                .contains("FOR SHARE OF e")
                .contains("<select id=\"nextSeqs\"")
                .contains("<insert id=\"insert\"")
                .contains("<insert id=\"insertBatch\"")
                .contains("collection=\"rows\"")
                .contains("#{row.tenantId}")
                .contains("#{row.userId}")
                .contains("#{row.createdAt, javaType=java.time.Instant, jdbcType=TIMESTAMP}")
                .contains("e.owner_instance_id = #{ownerInstanceId}")
                .contains("e.fencing_token = #{fencingToken}");

        String runMapper = Files.readString(
                MAPPER_XML_ROOT.resolve("persistence/ChatRunMapper.opengauss.xml"));
        assertThat(runMapper)
                .contains("<select id=\"lockSessionForInteractionContinuation\"")
                .contains("<select id=\"lockInteractionContinuationClaim\"")
                .contains("<update id=\"markCancelling\"")
                .contains("AND status = 'RUNNING'");

        String runRepository = Files.readString(Path.of(
                "src/main/java/com/huawei/it/ex/one/infrastructure/persistence/MyBatisChatRunRepository.java"));
        int sessionLock = runRepository.indexOf("mapper.lockSessionForInteractionContinuation(");
        int interactionLock = runRepository.indexOf("mapper.lockInteractionContinuationClaim(");
        int runInsert = runRepository.indexOf("mapper.insert(toRow(run));", interactionLock);
        assertThat(sessionLock).isGreaterThanOrEqualTo(0);
        assertThat(interactionLock).isGreaterThan(sessionLock);
        assertThat(runInsert).isGreaterThan(interactionLock);
    }

    @Test
    void interactionExecutionGateShouldAcceptAnsweredIntentClarificationOnly() throws IOException {
        String mapper = Files.readString(
                MAPPER_XML_ROOT.resolve("persistence/ChatRunExecutionMapper.opengauss.xml"));
        int start = mapper.indexOf("<update id=\"claimInteractionExecutionInitialization\"");
        int end = mapper.indexOf("</update>", start);

        assertThat(start).isGreaterThanOrEqualTo(0);
        assertThat(end).isGreaterThan(start);
        String initializationGate = mapper.substring(start, end);
        assertThat(initializationGate)
                .contains("r.status = 'RUNNING'")
                .contains("i.tenant_id = r.tenant_id")
                .contains("i.user_id = r.user_id")
                .contains("i.session_id = r.session_id")
                .contains("i.status = 'RESPONDING'")
                .contains("i.status = 'ANSWERED'")
                .contains("i.interaction_type = 'INTENT_CLARIFICATION'")
                .contains("i.continue_run_id = r.id");
    }

    @Test
    void executionHeartbeatShouldBeGuardedByOwnerAndFencingToken() throws IOException {
        String mapper = Files.readString(
                MAPPER_XML_ROOT.resolve("persistence/ChatRunExecutionMapper.opengauss.xml"));
        int start = mapper.indexOf("<update id=\"heartbeat\"");
        int end = mapper.indexOf("</update>", start);

        assertThat(start).isGreaterThanOrEqualTo(0);
        assertThat(end).isGreaterThan(start);
        assertThat(mapper.substring(start, end))
                .contains("owner_instance_id = #{ownerInstanceId}")
                .contains("fencing_token = #{fencingToken}")
                .contains("execution_status IN ('RUNNING', 'CANCELLING')");
    }

    @Test
    void executionHeartbeatBatchShouldPreserveCompleteClaimGuard() throws IOException {
        String mapper = Files.readString(
                MAPPER_XML_ROOT.resolve("persistence/ChatRunExecutionMapper.opengauss.xml"));
        int updateStart = mapper.indexOf("<update id=\"heartbeatBatch\"");
        int updateEnd = mapper.indexOf("</update>", updateStart);
        int readStart = mapper.indexOf("<select id=\"findHeartbeatEligibleClaims\"");
        int readEnd = mapper.indexOf("</select>", readStart);

        assertThat(updateStart).isGreaterThanOrEqualTo(0);
        assertThat(updateEnd).isGreaterThan(updateStart);
        assertThat(readStart).isGreaterThan(updateEnd);
        assertThat(readEnd).isGreaterThan(readStart);
        assertThat(mapper.substring(updateStart, updateEnd))
                .contains("execution_status IN ('RUNNING', 'CANCELLING')")
                .contains("(run_id, owner_instance_id, fencing_token) IN")
                .contains("#{claim.runId}")
                .contains("#{claim.ownerInstanceId}")
                .contains("#{claim.fencingToken}");
        assertThat(mapper.substring(readStart, readEnd))
                .contains("execution_status IN ('RUNNING', 'CANCELLING')")
                .contains("(run_id, owner_instance_id, fencing_token) IN");
    }

    @Test
    void chatMessagePartTypesShouldFitSchemaColumn() throws IOException {
        String schema = Files.readString(Path.of("src/main/resources/db/init-20260718.sql"));
        Matcher columnMatcher = MESSAGE_PART_TYPE_COLUMN.matcher(schema);

        assertThat(columnMatcher.find())
                .as("fin_ex_chat_message_part_t.part_type column must be declared in init-20260718.sql")
                .isTrue();
        int columnLength = Integer.parseInt(columnMatcher.group(1));
        List<String> oversizedTypes = CHAT_MESSAGE_PART_TYPES.stream()
                .filter(partType -> partType.length() > columnLength)
                .map(partType -> partType + "(" + partType.length() + ")")
                .toList();

        assertThat(oversizedTypes)
                .as("Chat message part types must fit VARCHAR(%s): %s", columnLength, oversizedTypes)
                .isEmpty();
    }

    @Test
    void chatSessionUnreadWatermarksShouldUseDedicatedUpdates() throws IOException {
        String schema = Files.readString(Path.of("src/main/resources/db/init-20260718.sql"));
        assertThat(schema)
                .contains("latest_message_seq BIGINT NOT NULL DEFAULT 0")
                .contains("last_read_seq BIGINT NOT NULL DEFAULT 0");

        String mapper = Files.readString(
                MAPPER_XML_ROOT.resolve("session/ChatSessionMapper.opengauss.xml"));
        String genericUpdate = mapper.substring(
                mapper.indexOf("<update id=\"update\""), mapper.indexOf("</update>", mapper.indexOf("<update id=\"update\"")));
        assertThat(genericUpdate)
                .doesNotContain("latest_message_seq")
                .doesNotContain("last_read_seq");
        assertThat(mapper)
                .contains("SET latest_message_seq = GREATEST(latest_message_seq, #{messageSeq})")
                .contains("SET last_read_seq = GREATEST(last_read_seq, LEAST(#{readThroughSeq}, latest_message_seq))");
        String advanceWatermark = mapper.substring(
                mapper.indexOf("<update id=\"advanceLatestMessageSeq\""),
                mapper.indexOf("</update>", mapper.indexOf("<update id=\"advanceLatestMessageSeq\"")));
        String markRead = mapper.substring(
                mapper.indexOf("<update id=\"markReadThrough\""),
                mapper.indexOf("</update>", mapper.indexOf("<update id=\"markReadThrough\"")));
        assertThat(advanceWatermark).doesNotContain("updated_at");
        assertThat(markRead).doesNotContain("updated_at");
    }

    @Test
    void routeMemoryIntentHistoryShouldExcludeFrontSelectedBeforeTopK() throws IOException {
        String mapper = Files.readString(
                MAPPER_XML_ROOT.resolve("persistence/RouteMemoryMapper.opengauss.xml"));
        int historyStart = mapper.indexOf("<select id=\"findRecentRoutes\"");
        int historyEnd = mapper.indexOf("</select>", historyStart);
        String historyQuery = mapper.substring(historyStart, historyEnd);
        int sourceFilter = historyQuery.indexOf(
                "AND (route_source IS NULL OR route_source &lt;&gt; 'front-selected')");
        int orderBy = historyQuery.indexOf("ORDER BY created_at DESC");
        int limit = historyQuery.indexOf("LIMIT #{limit}");

        assertThat(sourceFilter).isGreaterThanOrEqualTo(0).isLessThan(orderBy);
        assertThat(orderBy).isLessThan(limit);

        int fallbackStart = mapper.indexOf("<select id=\"latestRouteIsCompletedRelayFallback\"");
        int fallbackEnd = mapper.indexOf("</select>", fallbackStart);
        assertThat(mapper.substring(fallbackStart, fallbackEnd))
                .doesNotContain("route_source &lt;&gt; 'front-selected'");
    }

    private List<String> validateXmlMapper(Path xmlFile) {
        try {
            String xml = Files.readString(xmlFile);
            String namespace = requiredNamespace(xmlFile, xml);
            Path javaFile = MAIN_SOURCE_ROOT.resolve(namespace.replace('.', '/') + ".java");
            if (!Files.exists(javaFile)) {
                return List.of(xmlFile + " namespace points to missing Java Mapper: " + namespace);
            }

            Set<String> statementIds = collectStatementIds(xml);
            Set<String> methodNames = collectMapperMethods(javaFile);
            Set<String> missingStatements = new LinkedHashSet<>(methodNames);
            missingStatements.removeAll(statementIds);
            Set<String> extraStatements = new LinkedHashSet<>(statementIds);
            extraStatements.removeAll(methodNames);

            if (missingStatements.isEmpty() && extraStatements.isEmpty()) {
                return List.of();
            }
            return List.of(xmlFile + " missingStatements=" + missingStatements + ", extraStatements=" + extraStatements);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to inspect MyBatis XML: " + xmlFile, ex);
        }
    }

    private Path javaMapperFileFromXml(Path xmlFile) {
        try {
            String namespace = requiredNamespace(xmlFile, Files.readString(xmlFile));
            return MAIN_SOURCE_ROOT.resolve(namespace.replace('.', '/') + ".java");
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to inspect MyBatis XML namespace: " + xmlFile, ex);
        }
    }

    private String requiredNamespace(Path xmlFile, String xml) {
        Matcher matcher = NAMESPACE.matcher(xml);
        if (!matcher.find()) {
            throw new IllegalStateException("Missing MyBatis mapper namespace: " + xmlFile);
        }
        return matcher.group(1);
    }

    private Set<String> collectStatementIds(String xml) {
        Set<String> ids = new LinkedHashSet<>();
        Matcher matcher = STATEMENT_ID.matcher(xml);
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return ids;
    }

    private Set<String> collectMapperMethods(Path javaFile) throws IOException {
        Set<String> methods = new LinkedHashSet<>();
        StringBuilder signature = new StringBuilder();
        boolean collecting = false;
        for (String line : Files.readAllLines(javaFile)) {
            String trimmed = line.trim();
            if (!collecting && shouldSkipLine(trimmed)) {
                continue;
            }
            if (!collecting && trimmed.contains("(")) {
                collecting = true;
                signature.setLength(0);
            }
            if (collecting) {
                signature.append(' ').append(trimmed);
                if (trimmed.endsWith(";")) {
                    String beforeArguments = signature.toString().split("\\(", 2)[0].trim();
                    String[] tokens = beforeArguments.split("\\s+");
                    methods.add(tokens[tokens.length - 1]);
                    collecting = false;
                }
            }
        }
        return methods;
    }

    private boolean shouldSkipLine(String line) {
        return line.isBlank()
                || line.startsWith("package ")
                || line.startsWith("import ")
                || line.startsWith("@")
                || line.startsWith("//")
                || line.startsWith("*")
                || line.startsWith("/*")
                || line.startsWith("public interface ")
                || line.equals("}");
    }

    private boolean containsForbiddenSqlAnnotation(Path path) {
        try {
            return SQL_ANNOTATION.matcher(Files.readString(path)).find();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to inspect Java source: " + path, ex);
        }
    }

    private boolean containsSelectWildcardProjection(Path path) {
        try {
            return SELECT_WILDCARD_PROJECTION.matcher(Files.readString(path)).find();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to inspect MyBatis SELECT projection: " + path, ex);
        }
    }

    private List<String> findInsertSelectViolations(Path path) {
        try {
            String xml = XML_COMMENT.matcher(Files.readString(path)).replaceAll(" ");
            List<String> violations = new ArrayList<>();
            Matcher statementMatcher = STATEMENT_BLOCK.matcher(xml);
            while (statementMatcher.find()) {
                if (!"insert".equalsIgnoreCase(statementMatcher.group(1))) {
                    continue;
                }
                String sql = XML_TAG.matcher(statementMatcher.group(3)).replaceAll(" ");
                sql = SQL_STRING_LITERAL.matcher(sql).replaceAll("''");
                if (INSERT_SELECT.matcher(sql).find()) {
                    violations.add(MAPPER_XML_ROOT.relativize(path) + "#" + statementMatcher.group(2));
                }
            }
            return List.copyOf(violations);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to inspect MyBatis INSERT statements: " + path, ex);
        }
    }

    private List<String> findPredicateExpressionViolations(Path path) {
        try {
            String xml = XML_COMMENT.matcher(Files.readString(path)).replaceAll(" ");
            List<String> violations = new ArrayList<>();
            Matcher statementMatcher = STATEMENT_BLOCK.matcher(xml);
            while (statementMatcher.find()) {
                String statementId = statementMatcher.group(2);
                String sql = XML_TAG.matcher(statementMatcher.group(3)).replaceAll(" ");
                Matcher predicateMatcher = PREDICATE_CLAUSE.matcher(sql);
                while (predicateMatcher.find()) {
                    String predicate = SQL_STRING_LITERAL.matcher(predicateMatcher.group(1)).replaceAll("''");
                    predicate = MYBATIS_PARAMETER.matcher(predicate).replaceAll("?");
                    if (PREDICATE_COLUMN_FUNCTION.matcher(predicate).find()) {
                        violations.add(MAPPER_XML_ROOT.relativize(path) + "#" + statementId
                                + " uses a column transformation function in a predicate");
                    }
                    if (PREDICATE_COLUMN_ARITHMETIC.matcher(predicate).find()) {
                        violations.add(MAPPER_XML_ROOT.relativize(path) + "#" + statementId
                                + " uses column arithmetic in a predicate");
                    }
                }
            }
            return List.copyOf(new LinkedHashSet<>(violations));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to inspect MyBatis predicate expressions: " + path, ex);
        }
    }

    private List<String> findStatementsWithoutBusinessComment(Path xmlFile) {
        try {
            List<String> lines = Files.readAllLines(xmlFile);
            Set<String> violations = new LinkedHashSet<>();
            for (int i = 0; i < lines.size(); i++) {
                Matcher matcher = STATEMENT_LINE.matcher(lines.get(i));
                if (!matcher.matches() || hasPreviousXmlComment(lines, i)) {
                    continue;
                }
                violations.add(xmlFile + ":" + (i + 1) + " statement " + matcher.group(2)
                        + " is missing an explanatory XML comment");
            }
            return List.copyOf(violations);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to inspect MyBatis XML comments: " + xmlFile, ex);
        }
    }

    private boolean hasPreviousXmlComment(List<String> lines, int statementLineIndex) {
        boolean foundEnd = false;
        StringBuilder comment = new StringBuilder();
        for (int i = statementLineIndex - 1; i >= 0; i--) {
            String previous = lines.get(i).trim();
            if (previous.isBlank()) {
                continue;
            }
            if (!foundEnd) {
                if (!previous.endsWith("-->")) {
                    return false;
                }
                foundEnd = true;
            }
            comment.insert(0, previous).insert(0, ' ');
            if (previous.startsWith("<!--")) {
                return comment.toString().contains("入参");
            }
        }
        return false;
    }

    private List<String> findMapperMethodsWithoutJavadocs(Path javaFile) {
        try {
            List<String> lines = Files.readAllLines(javaFile);
            List<String> violations = new java.util.ArrayList<>();
            StringBuilder signature = new StringBuilder();
            int signatureStart = -1;
            boolean collecting = false;
            for (int i = 0; i < lines.size(); i++) {
                String trimmed = lines.get(i).trim();
                if (!collecting && shouldSkipLine(trimmed)) {
                    continue;
                }
                if (!collecting && trimmed.contains("(")) {
                    collecting = true;
                    signature.setLength(0);
                    signatureStart = i;
                }
                if (collecting) {
                    signature.append(' ').append(trimmed);
                    if (trimmed.endsWith(";")) {
                        String methodSignature = signature.toString();
                        String beforeArguments = methodSignature.split("\\(", 2)[0].trim();
                        String[] tokens = beforeArguments.split("\\s+");
                        String methodName = tokens[tokens.length - 1];
                        String javaDoc = previousJavadoc(lines, signatureStart);
                        if (javaDoc.isBlank()) {
                            violations.add(javaFile + ":" + (signatureStart + 1) + " method " + methodName
                                    + " is missing JavaDoc");
                        } else {
                            for (String parameterName : methodParameterNames(methodSignature)) {
                                if (!javaDoc.contains("@param " + parameterName)) {
                                    violations.add(javaFile + ":" + (signatureStart + 1) + " method " + methodName
                                            + " should document parameter '" + parameterName + "'");
                                }
                            }
                        }
                        collecting = false;
                    }
                }
            }
            return violations;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to inspect Java Mapper comments: " + javaFile, ex);
        }
    }

    private List<String> methodParameterNames(String methodSignature) {
        String arguments = methodSignature.substring(methodSignature.indexOf('(') + 1, methodSignature.lastIndexOf(')'));
        if (arguments.isBlank()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (String parameter : splitParameters(arguments)) {
            String[] tokens = parameter.trim().split("\\s+");
            if (tokens.length > 0) {
                names.add(tokens[tokens.length - 1].replace("...", "").replace("[]", ""));
            }
        }
        return names;
    }

    private List<String> splitParameters(String arguments) {
        List<String> parameters = new ArrayList<>();
        int angleDepth = 0;
        int parenDepth = 0;
        int start = 0;
        for (int i = 0; i < arguments.length(); i++) {
            char ch = arguments.charAt(i);
            if (ch == '<') {
                angleDepth++;
            } else if (ch == '>') {
                angleDepth = Math.max(0, angleDepth - 1);
            } else if (ch == '(') {
                parenDepth++;
            } else if (ch == ')') {
                parenDepth = Math.max(0, parenDepth - 1);
            } else if (ch == ',' && angleDepth == 0 && parenDepth == 0) {
                parameters.add(arguments.substring(start, i));
                start = i + 1;
            }
        }
        parameters.add(arguments.substring(start));
        return parameters;
    }

    private String previousJavadoc(List<String> lines, int signatureStart) {
        boolean foundEnd = false;
        StringBuilder javaDoc = new StringBuilder();
        for (int i = signatureStart - 1; i >= 0; i--) {
            String previous = lines.get(i).trim();
            if (previous.isBlank()) {
                continue;
            }
            if (!foundEnd) {
                if (!previous.endsWith("*/")) {
                    return "";
                }
                foundEnd = true;
            }
            javaDoc.insert(0, previous).insert(0, ' ');
            if (previous.startsWith("/**")) {
                return javaDoc.toString();
            }
        }
        return "";
    }
}
