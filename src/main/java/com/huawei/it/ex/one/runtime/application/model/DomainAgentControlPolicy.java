package com.huawei.it.ex.one.runtime.application.model;

/** Runtime-owned policy snapshot used by the DomainAgent refusal coordinator. */
public record DomainAgentControlPolicy(int maxReroutes) {
}
