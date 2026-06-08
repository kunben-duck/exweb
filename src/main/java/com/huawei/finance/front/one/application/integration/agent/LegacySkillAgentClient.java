package com.huawei.finance.front.one.application.integration.agent;

import com.huawei.finance.front.one.domain.chat.ChatEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 老 Agent 指定技能防腐层。
 *
 * <p>该端口只服务前端显式选择 skillId 的历史兼容场景。它不代表默认复杂任务 Runtime，也不创建
 * RuntimeBinding；下游私有 chat/upload 协议必须在实现层转换为 ChatService 标准事件。</p>
 */
public interface LegacySkillAgentClient {
    /**
     * 调用老 Agent 指定技能 chat 流式接口。
     *
     * @param request 指定技能调用请求。
     * @return ChatService 标准事件流。
     */
    Flux<ChatEvent> query(LegacySkillAgentRequest request);

    /**
     * 尽力取消老 Agent 指定技能 run。
     *
     * @param request 取消请求。
     * @return 完成信号。
     */
    Mono<Void> cancel(LegacySkillCancelRequest request);
}
