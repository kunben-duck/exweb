package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.agent.MessageSkillContext;

import java.util.concurrent.atomic.AtomicReference;

/** 单个run内保存最后一次已提交路由对应的消息技能标识。 */
final class MessageSkillTracker {
    private final AtomicReference<String> skillId = new AtomicReference<>();

    void replace(String nextSkillId) {
        skillId.set(MessageSkillContext.normalizeSkillId(nextSkillId));
    }

    String current() {
        return skillId.get();
    }
}
