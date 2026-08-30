/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.regex.Pattern;

class SystemErrorCodeTest {
    private static final Pattern CODE_PATTERN =
            Pattern.compile("^FN-EX-CHAT-SYS-[A-Z0-9]{2,4}-[0-9]{3}$");

    @Test
    void catalogCodesAreUniqueWellFormedAndMatchTheirOrigin() {
        assertThat(Arrays.stream(SystemErrorCode.values()).map(SystemErrorCode::code))
                .allMatch(code -> CODE_PATTERN.matcher(code).matches())
                .doesNotHaveDuplicates();
        assertThat(Arrays.stream(SystemErrorCode.values()).map(SystemErrorCode::reasonCode))
                .doesNotHaveDuplicates();
        assertThat(Arrays.stream(SystemErrorCode.values()))
                .allMatch(code -> code.code().contains("-" + code.origin() + "-"));
    }

    @Test
    void preservesPublishedDomainAgentCodesAndResolvesRegisteredUpstreamCodes() {
        assertThat(SystemErrorCode.AGENT_OVERLOADED.code()).isEqualTo("FN-EX-CHAT-SYS-DAG-001");
        assertThat(SystemErrorCode.DOMAIN_AGENT_TIMEOUT.code()).isEqualTo("FN-EX-CHAT-SYS-DAG-002");
        assertThat(SystemErrorCode.DOMAIN_AGENT_RATE_LIMITED.code()).isEqualTo("FN-EX-CHAT-SYS-DAG-003");
        assertThat(SystemErrorCode.DOMAIN_AGENT_EXECUTION_FAILED.code())
                .isEqualTo("FN-EX-CHAT-SYS-DAG-004");
        assertThat(SystemErrorCode.PROTOCOL_INVALID.code()).isEqualTo("FN-EX-CHAT-SYS-DAG-005");
        assertThat(SystemErrorCode.RESPONSE_PARSE_FAILED.code()).isEqualTo("FN-EX-CHAT-SYS-DAG-006");

        assertThat(SystemErrorCode.fromCode("FN-EX-CHAT-SYS-MQS-002"))
                .contains(SystemErrorCode.MQS_TIMEOUT);
        assertThat(SystemErrorCode.fromRegisteredUpstreamCode("FN-EX-CHAT-SYS-MQS-002"))
                .contains(SystemErrorCode.MQS_TIMEOUT);
        assertThat(SystemErrorCode.fromRegisteredUpstreamCode("FN-EX-CHAT-SYS-DBS-004"))
                .isEmpty();
        assertThat(SystemErrorCode.fromCode("FN-EX-CHAT-SYS-UNKNOWN-001")).isEmpty();
    }

    @Test
    void unknownUpstreamCodeFallsBackWithinItsRegisteredOrigin() {
        assertThat(SystemErrorCode.fallbackForOrigin("DAG"))
                .isEqualTo(SystemErrorCode.DOMAIN_AGENT_ERROR);
        assertThat(SystemErrorCode.fallbackForOrigin("MQS"))
                .isEqualTo(SystemErrorCode.MQS_ERROR);
        assertThat(SystemErrorCode.fallbackForOrigin("dag"))
                .isEqualTo(SystemErrorCode.DOMAIN_AGENT_ERROR);
        assertThat(SystemErrorCode.fallbackForOrigin("unknown"))
                .isEqualTo(SystemErrorCode.UNKNOWN_SYSTEM_ERROR);
    }
}
