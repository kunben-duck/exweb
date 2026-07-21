package com.huawei.it.ex.one.interfaces.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TrustedClientIpResolverTest {
    private final TrustedClientIpResolver resolver = new TrustedClientIpResolver();

    @Test
    void normalizesIpv4AndIpv6Literals() {
        assertThat(resolver.resolve(" 203.0.113.007 ")).isEqualTo("203.0.113.7");
        assertThat(resolver.resolve("2001:0db8:0:0:0:0:0:1")).isEqualTo("2001:db8:0:0:0:0:0:1");
    }

    @Test
    void rejectsForwardedListsHostNamesAndInvalidAddresses() {
        assertThat(resolver.resolve("203.0.113.7, 10.0.0.1")).isEmpty();
        assertThat(resolver.resolve("example.com")).isEmpty();
        assertThat(resolver.resolve("999.0.0.1")).isEmpty();
        assertThat(resolver.resolve(null)).isEmpty();
    }
}
