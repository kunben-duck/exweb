package com.huawei.finance.front.one.infrastructure.usecase;

import com.huawei.finance.front.one.application.gateway.UseCaseLibraryClient;
import com.huawei.finance.front.one.application.gateway.UseCaseMatchRequest;
import com.huawei.finance.front.one.domain.usecase.UseCaseMatchResult;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "financeex.use-case-library.provider", havingValue = "mock", matchIfMissing = true)
public class MockUseCaseLibraryClient implements UseCaseLibraryClient {
    @Override
    public UseCaseMatchResult match(UseCaseMatchRequest request) {
        String message = request.message() == null ? "" : request.message();
        if (message.contains("员工") || message.contains("工号")) {
            return new UseCaseMatchResult(true, 0.92, "finance.employee.agent", "mock employee use case", Map.of(), Map.of("source", "mock"));
        }
        if (message.contains("代表处") || message.contains("办事处") || message.contains("国家")) {
            return new UseCaseMatchResult(true, 0.92, "finance.office.agent", "mock office use case", Map.of(), Map.of("source", "mock"));
        }
        return UseCaseMatchResult.notMatched("mock no use case");
    }
}
