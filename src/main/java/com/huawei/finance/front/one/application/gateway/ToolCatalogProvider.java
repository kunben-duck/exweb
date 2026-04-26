package com.huawei.finance.front.one.application.gateway;

import com.huawei.finance.front.one.domain.tool.ToolDefinition;
import java.util.List;
import java.util.Optional;

public interface ToolCatalogProvider {
    String providerCode();
    List<ToolDefinition> listTools(String tenantId, String keyword, String category);
    Optional<ToolDefinition> getTool(String tenantId, String toolCode);
}
