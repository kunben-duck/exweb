package com.huawei.finance.front.one.application.integration.agent;

import com.huawei.finance.front.one.domain.agent.AgentQueryRequest;
import com.huawei.finance.front.one.domain.agent.SubAgentCancelRequest;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 第三方 SubAgent 调用端口。
 *
 * <p>SubAgent 当前只承担简单任务单轮执行。应用层只依赖该端口，不感知下游是 HTTP、
 * A2A 还是企业内部私有协议。</p>
 */
public interface SubAgentClient {
    /**
     * 调用指定 SubAgent 并返回统一 ChatEvent 流。
     *
     * @param request SubAgent 查询请求，包含目标 agentCode、用户输入、附件和上下文快照。
     * @return 标准聊天事件流。
     */
    Flux<ChatEvent> query(AgentQueryRequest request);

    /**
     * 尽力取消已提交给 SubAgent 的 run。
     *
     * @param request SubAgent 取消请求，包含 runId、agentCode 和取消原因。
     * @return 完成信号；下游不支持取消时应返回空完成。
     */
    Mono<Void> cancel(SubAgentCancelRequest request);
}
