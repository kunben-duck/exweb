package com.huawei.it.ex.one.application.service.share;

/**
 * 分享标题规范化工具。
 */
final class ChatShareTitleNormalizer {
    static final int MAX_CODE_POINTS = 120;
    static final int MAX_UTF8_BYTES = 256;

    private ChatShareTitleNormalizer() {
    }

    static String normalize(String value, String fallback) {
        String candidate = singleLine(value);
        if (candidate == null) {
            candidate = singleLine(fallback);
        }
        if (candidate == null) {
            return "";
        }

        StringBuilder normalized = new StringBuilder(Math.min(candidate.length(), MAX_CODE_POINTS));
        int utf8Bytes = 0;
        int codePoints = 0;
        for (int offset = 0; offset < candidate.length() && codePoints < MAX_CODE_POINTS; codePoints++) {
            int codePoint = candidate.codePointAt(offset);
            int codePointBytes = utf8Bytes(codePoint);
            if (utf8Bytes + codePointBytes > MAX_UTF8_BYTES) {
                break;
            }
            normalized.appendCodePoint(codePoint);
            utf8Bytes += codePointBytes;
            offset += Character.charCount(codePoint);
        }
        return normalized.toString();
    }

    private static String singleLine(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static int utf8Bytes(int codePoint) {
        if (codePoint <= 0x7F) {
            return 1;
        }
        if (codePoint <= 0x7FF) {
            return 2;
        }
        if (codePoint <= 0xFFFF) {
            return 3;
        }
        return 4;
    }
}
