package com.huawei.finance.front.one.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.gateway.ToolCatalogProvider;
import com.huawei.finance.front.one.domain.tool.ToolDefinition;
import com.huawei.finance.front.one.domain.tool.ToolInvocationMode;
import com.huawei.finance.front.one.domain.tool.ToolRiskLevel;
import com.huawei.finance.front.one.domain.tool.ToolSourceType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 本地工具目录实现。
 *
 * <p>第一版用代码内置工具定义模拟数据库目录，后续可替换为真实 tool_definition 表查询。</p>
 */
@Component
public class LocalDbToolCatalogProvider implements ToolCatalogProvider {
    private final ObjectMapper objectMapper;
    public LocalDbToolCatalogProvider(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }
    @Override public String providerCode() { return "local-db"; }
    @Override public List<ToolDefinition> listTools(String tenantId, String keyword, String category) {
        // tenant/category 当前未真正过滤，保留参数是为了和未来数据库实现保持一致。
        List<ToolDefinition> tools = List.of(employeeQuery(), officeQuery());
        return tools.stream().filter(t -> keyword == null || t.name().contains(keyword) || t.description().contains(keyword)).toList();
    }
    @Override public Optional<ToolDefinition> getTool(String tenantId, String toolCode) { return listTools(tenantId, null, null).stream().filter(t -> t.toolCode().equals(toolCode)).findFirst(); }
    private ToolDefinition employeeQuery() { return new ToolDefinition("finance.employee.query", "员工信息查询", "根据员工工号或姓名查询员工基础信息", "finance", "mock-third-party", "employeeQuery", ToolSourceType.LOCAL_DB, ToolInvocationMode.SYNC, ToolRiskLevel.READ_ONLY, Set.of("finance:tool:read"), schema("employeeNo", "employeeName"), schema("result"), true, false, Map.of()); }
    private ToolDefinition officeQuery() { return new ToolDefinition("finance.office.query", "代表处办事处查询", "根据国家或代表处查询办事处信息", "finance", "mock-third-party", "officeQuery", ToolSourceType.LOCAL_DB, ToolInvocationMode.SYNC, ToolRiskLevel.READ_ONLY, Set.of("finance:tool:read"), schema("country", "repOffice"), schema("offices"), true, false, Map.of()); }
    private JsonNode schema(String... fields) {
        // 生成最小 JSON Schema，方便前端展示工具入参和 Agent 了解字段。
        var props = objectMapper.createObjectNode();
        for (String f : fields) props.putObject(f).put("type", "string");
        return objectMapper.createObjectNode().put("type", "object").set("properties", props);
    }
}
