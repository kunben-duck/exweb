package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.application.facade.ToolCatalogFacade;
import com.huawei.finance.front.one.application.gateway.AuthContextProvider;
import com.huawei.finance.front.one.application.gateway.ToolCatalogProvider;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.tool.ToolDefinition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ToolCatalogApplicationService implements ToolCatalogFacade {
    private final List<ToolCatalogProvider> providers;
    private final AuthContextProvider auth;
    private final PermissionChecker permissionChecker;
    public ToolCatalogApplicationService(List<ToolCatalogProvider> providers, AuthContextProvider auth, PermissionChecker permissionChecker) {
        this.providers = providers; this.auth = auth; this.permissionChecker = permissionChecker;
    }
    @Override
    public List<ToolDefinition> listTools(String tenantId, String userId, String keyword, String category) {
        UserContext user = auth.resolve(tenantId, userId);
        Map<String, ToolDefinition> merged = new LinkedHashMap<>();
        for (ToolCatalogProvider provider : providers) {
            for (ToolDefinition tool : provider.listTools(tenantId, keyword, category)) {
                if (tool.enabled()) merged.putIfAbsent(tool.toolCode(), tool);
            }
        }
        return merged.values().stream().filter(t -> visible(user, t)).toList();
    }
    public List<ToolDefinition> listAgentVisibleTools(String tenantId, String userId) { return listTools(tenantId, userId, null, null); }
    @Override
    public ToolDefinition getTool(String tenantId, String userId, String toolCode) {
        UserContext user = auth.resolve(tenantId, userId);
        ToolDefinition tool = providers.stream()
                .map(p -> p.getTool(tenantId, toolCode))
                .flatMap(java.util.Optional::stream)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("工具不存在: " + toolCode));
        permissionChecker.checkToolVisible(user, tool);
        return tool;
    }
    private boolean visible(UserContext user, ToolDefinition tool) {
        try { permissionChecker.checkToolVisible(user, tool); return true; } catch (Exception ex) { return false; }
    }
}
