package com.huawei.it.ex.one.application.integration.intent;

/** Resolves the effective Intent service entry for a request. */
public interface IntentAccessNameResolver {
    /**
     * Uses the explicitly supplied entry when present and otherwise falls back to the server default.
     *
     * @param requestedAccessName optional request-level Intent entry.
     * @return normalized effective entry, or an empty string when neither source is configured.
     */
    String resolve(String requestedAccessName);
}
