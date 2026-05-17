package com.huawei.finance.front.one.application.integration.usecase;

import com.huawei.finance.front.one.domain.usecase.UseCaseMatchResult;

/**
 * 用例库路由信号端口。
 *
 * <p>用例库是可选外部信号，默认关闭。开启后只用于首轮路由，不作为聊天主链路强依赖。</p>
 */
public interface UseCaseLibraryClient {
    /**
     * 根据用户请求和上下文匹配已有用例。
     *
     * @param request 用例库匹配请求，包含用户输入、附件和上下文快照。
     * @return 用例匹配结果，未命中时 matched=false。
     */
    UseCaseMatchResult match(UseCaseMatchRequest request);
}
