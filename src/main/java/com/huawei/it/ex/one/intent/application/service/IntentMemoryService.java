package com.huawei.it.ex.one.intent.application.service;

import com.huawei.it.ex.one.intent.application.model.IntentMemoryRequest;
import com.huawei.it.ex.one.intent.application.model.MemoryContext;

/** Application boundary for loading the intent-facing memory snapshot of one run. */
public interface IntentMemoryService {
    MemoryContext loadForRun(IntentMemoryRequest request);
}
