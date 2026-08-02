package com.huawei.it.ex.one.application.integration.sessiontitle;

import reactor.core.publisher.Mono;

/** 会话标题总结服务防腐接口。 */
public interface SessionTitleProvider {
    /**
     * 根据当前业务问题路径生成会话标题。
     *
     * @param request 中立的标题总结请求。
     * @return 第三方返回的原始标题；空响应必须以异常结束。
     */
    Mono<String> generate(SessionTitleRequest request);
}
