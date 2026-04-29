package com.huawei.finance.front.one.application.gateway;

import com.huawei.finance.front.one.domain.usecase.UseCaseMatchResult;

public interface UseCaseLibraryClient {
    UseCaseMatchResult match(UseCaseMatchRequest request);
}
