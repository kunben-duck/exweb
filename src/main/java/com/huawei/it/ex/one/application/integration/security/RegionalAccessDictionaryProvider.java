package com.huawei.it.ex.one.application.integration.security;

/**
 * 地域准入数据字典 Provider。
 *
 * <p>实现负责从可信数据源读取员工白名单和欧盟国家集合，并向应用层提供不可变快照。</p>
 */
public interface RegionalAccessDictionaryProvider {
    /**
     * 获取当前地域准入字典快照。
     *
     * @return 当前员工白名单和欧盟国家集合。
     */
    RegionalAccessDictionarySnapshot currentSnapshot();
}
