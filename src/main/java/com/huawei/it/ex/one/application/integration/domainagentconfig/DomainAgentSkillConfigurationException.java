package com.huawei.it.ex.one.application.integration.domainagentconfig;

/** DomainAgent 技能配置查询失败。 */
public class DomainAgentSkillConfigurationException extends RuntimeException {
    private final Reason reason;

    public DomainAgentSkillConfigurationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public DomainAgentSkillConfigurationException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    /** 供应用层稳定区分可用性、超时和协议错误。 */
    public enum Reason {
        UNAVAILABLE,
        TIMEOUT,
        PROTOCOL_INVALID
    }
}
