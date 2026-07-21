package com.huawei.it.ex.one.intent.application.service;

import com.huawei.it.ex.one.intent.application.model.IntentRecognitionRecordSnapshot;

/** Application boundary for asynchronous intent-recognition audit records. */
public interface IntentRecognitionService {
    void recordAsync(IntentRecognitionRecordSnapshot snapshot);
}
