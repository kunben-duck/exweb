package com.huawei.it.ex.one.infrastructure.security;

import com.huawei.it.ex.one.application.integration.security.RegionalAccessDictionaryProvider;
import com.huawei.it.ex.one.application.integration.security.RegionalAccessDictionarySnapshot;

/**
 * 企业地域准入数据字典适配器。
 *
 * <p>白名单和欧盟国家集合的企业框架读取逻辑统一收口在
 * {@link #loadRegionalAccessDictionary()}。应用层只消费不可变字典快照，不依赖企业数据字典 SDK。</p>
 */
public final class EnterpriseRegionalAccessDictionaryProvider implements RegionalAccessDictionaryProvider {
    @Override
    public RegionalAccessDictionarySnapshot currentSnapshot() {
        return loadRegionalAccessDictionary();
    }

    private RegionalAccessDictionarySnapshot loadRegionalAccessDictionary() {
        // TODO 接入企业数据字典框架，返回员工白名单和欧盟国家名称集合。
        return RegionalAccessDictionarySnapshot.empty();
    }
}
