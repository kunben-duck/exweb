package com.huawei.finance.front.one.application.facade;

import com.huawei.finance.front.one.domain.tool.ToolDefinition;
import java.util.List;

public interface ToolCatalogFacade {
    List<ToolDefinition> listTools(String tenantId, String userId, String keyword, String category);
    ToolDefinition getTool(String tenantId, String userId, String toolCode);
}
