package com.huawei.finance.front.one.application.integration.usecase;

import com.huawei.finance.front.one.domain.usecase.UseCaseMatchResult;

public interface UseCaseLibraryClient {
    UseCaseMatchResult match(UseCaseMatchRequest request);
}
