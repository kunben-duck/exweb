package com.huawei.it.ex.one.application.service.security;

/**
 * Raised before entering business processing when service use is restricted by region.
 */
public class RegionalAccessDeniedException extends SecurityException {
    public static final String CODE = "SERVICE_REGION_RESTRICTED";
    public static final String DEFAULT_MESSAGE = "根据服务可用地区政策，您所在地区暂不支持使用本服务。";

    public RegionalAccessDeniedException() {
        super(DEFAULT_MESSAGE);
    }
}
