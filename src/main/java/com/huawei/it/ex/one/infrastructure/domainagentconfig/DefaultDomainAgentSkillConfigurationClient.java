package com.huawei.it.ex.one.infrastructure.domainagentconfig;

import java.util.List;

/** 默认企业技能配置Client，当前调用点等待企业框架依赖接入。 */
public final class DefaultDomainAgentSkillConfigurationClient
        implements DomainAgentSkillConfigurationClient {
    public static final String OPERATION_NAME = "findSkillConfigBySkillIds";

    @Override
    public SkillConfigurationResponse findBySkillIds(List<String> skillIds) {
        /*
         * TODO 接入企业 Jalor 依赖后，用以下调用替换当前占位实现：
         * HttpEntity<List<String>> requestEntity = new HttpEntity<>(skillIds);
         * ResponseEntity<SkillConfigurationResponse> result = jalorRestTemplate.exchangeInApp(
         *         OPERATION_NAME, requestEntity, SkillConfigurationResponse.class, null, null);
         * return result.getBody();
         */
        throw new IllegalStateException("DomainAgent skill configuration client is not configured");
    }
}
