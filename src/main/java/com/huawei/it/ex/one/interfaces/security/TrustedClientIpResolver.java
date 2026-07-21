package com.huawei.it.ex.one.interfaces.security;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Validates and normalizes the gateway-owned X-Real-IP value without accepting host names.
 */
@Component
public class TrustedClientIpResolver {
    public String resolve(String headerValue) {
        if (headerValue == null) {
            return "";
        }
        String candidate = headerValue.trim();
        if (candidate.isEmpty() || candidate.length() > 64 || candidate.indexOf(',') >= 0) {
            return "";
        }
        if (candidate.indexOf(':') >= 0) {
            return normalizeIpv6(candidate);
        }
        return normalizeIpv4(candidate);
    }

    private String normalizeIpv4(String candidate) {
        String[] segments = candidate.split("\\.", -1);
        if (segments.length != 4) {
            return "";
        }
        StringBuilder normalized = new StringBuilder();
        for (String segment : segments) {
            if (segment.isEmpty() || segment.length() > 3 || !segment.chars().allMatch(Character::isDigit)) {
                return "";
            }
            int value;
            try {
                value = Integer.parseInt(segment);
            } catch (NumberFormatException ex) {
                return "";
            }
            if (value > 255) {
                return "";
            }
            if (!normalized.isEmpty()) {
                normalized.append('.');
            }
            normalized.append(value);
        }
        return normalized.toString();
    }

    private String normalizeIpv6(String candidate) {
        if (!candidate.matches("[0-9a-fA-F:.]+")) {
            return "";
        }
        try {
            InetAddress address = InetAddress.getByName(candidate);
            if (!(address instanceof Inet6Address)) {
                return "";
            }
            return address.getHostAddress().toLowerCase(Locale.ROOT);
        } catch (UnknownHostException ex) {
            return "";
        }
    }
}
