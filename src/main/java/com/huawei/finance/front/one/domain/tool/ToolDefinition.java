package com.huawei.finance.front.one.domain.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Set;

/**
 * 工具定义。
 *
 * <p>描述工具目录中的可调用能力，包括 provider、风险等级、权限范围和入出参 schema。</p>
 */
public record ToolDefinition(
        String toolCode,
        String name,
        String description,
        String category,
        String providerCode,
        String providerToolId,
        ToolSourceType sourceType,
        ToolInvocationMode invocationMode,
        ToolRiskLevel riskLevel,
        Set<String> requiredScopes,
        JsonNode inputSchema,
        JsonNode outputSchema,
        boolean enabled,
        boolean requiresConfirmation,
        Map<String, Object> extension
) {}
