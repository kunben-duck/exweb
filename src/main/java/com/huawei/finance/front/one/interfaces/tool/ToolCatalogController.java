package com.huawei.finance.front.one.interfaces.tool;

import com.huawei.finance.front.one.application.facade.ToolCatalogFacade;
import com.huawei.finance.front.one.domain.tool.ToolDefinition;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/finance/tools")
public class ToolCatalogController {
    private final ToolCatalogFacade facade;
    public ToolCatalogController(ToolCatalogFacade facade) { this.facade = facade; }
    @GetMapping
    public List<ToolDefinition> list(@RequestParam(required = false) String keyword, @RequestParam(required = false) String category,
                                     @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
                                     @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId) {
        return facade.listTools(tenantId, userId, keyword, category);
    }
    @GetMapping("/{toolCode}")
    public ToolDefinition detail(@PathVariable String toolCode,
                                 @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
                                 @RequestHeader(value = "X-User-Id", defaultValue = "anonymous") String userId) {
        return facade.getTool(tenantId, userId, toolCode);
    }
}
