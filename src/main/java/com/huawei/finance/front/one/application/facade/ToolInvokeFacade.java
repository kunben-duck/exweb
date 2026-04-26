package com.huawei.finance.front.one.application.facade;

import com.huawei.finance.front.one.domain.tool.ToolInvocationEvent;
import com.huawei.finance.front.one.domain.tool.ToolInvokeCommand;
import reactor.core.publisher.Flux;

public interface ToolInvokeFacade { Flux<ToolInvocationEvent> invoke(ToolInvokeCommand command); }
