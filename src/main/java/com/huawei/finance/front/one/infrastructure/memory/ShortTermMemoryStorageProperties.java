package com.huawei.finance.front.one.infrastructure.memory;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "financeex.memory.short-term.storage")
public class ShortTermMemoryStorageProperties {
    private boolean databaseRequired = false;
    private Duration databaseFailureBackoff = Duration.ofSeconds(30);

    public boolean isDatabaseRequired() {
        return databaseRequired;
    }

    public void setDatabaseRequired(boolean databaseRequired) {
        this.databaseRequired = databaseRequired;
    }

    public Duration getDatabaseFailureBackoff() {
        return databaseFailureBackoff;
    }

    public void setDatabaseFailureBackoff(Duration databaseFailureBackoff) {
        this.databaseFailureBackoff = databaseFailureBackoff;
    }
}
