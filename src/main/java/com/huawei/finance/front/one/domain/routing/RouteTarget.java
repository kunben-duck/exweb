package com.huawei.finance.front.one.domain.routing;

public record RouteTarget(RouteType type, RuntimeProtocol runtimeProtocol, String reason) {
    public static RouteTarget local(String reason) { return new RouteTarget(RouteType.LOCAL_AGENT, null, reason); }
    public static RouteTarget relay(RuntimeProtocol protocol, String reason) { return new RouteTarget(RouteType.RELAY_AGENT, protocol, reason); }
    public static RouteTarget clarification(String reason) { return new RouteTarget(RouteType.ASK_CLARIFICATION, null, reason); }
    public static RouteTarget reject(String reason) { return new RouteTarget(RouteType.REJECT, null, reason); }
}
