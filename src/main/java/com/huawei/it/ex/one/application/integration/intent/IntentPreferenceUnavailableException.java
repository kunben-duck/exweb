package com.huawei.it.ex.one.application.integration.intent;

/** Preference recording is temporarily unavailable without affecting an already accepted chat run. */
public class IntentPreferenceUnavailableException extends RuntimeException {
    public IntentPreferenceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
