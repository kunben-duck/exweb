package com.huawei.it.ex.one.application.service.chat;

/** 无临时编码数组地计算UTF-8大小并按Unicode边界截断文本。 */
final class Utf8Text {
    private Utf8Text() {
    }

    static long bytes(String value) {
        if (value == null || value.isEmpty()) {
            return 0L;
        }
        long bytes = 0L;
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)
                    && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                bytes += 4L;
                index += 2;
                continue;
            }
            bytes += encodedBytes(current);
            index++;
        }
        return bytes;
    }

    static Prefix prefix(String value, long maxBytes) {
        if (value == null || value.isEmpty()) {
            return new Prefix("", 0L, false);
        }
        if (maxBytes <= 0L) {
            return new Prefix("", 0L, true);
        }
        long used = 0L;
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            int chars = 1;
            int nextBytes;
            if (Character.isHighSurrogate(current)
                    && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                chars = 2;
                nextBytes = 4;
            } else {
                nextBytes = encodedBytes(current);
            }
            if (used > maxBytes - nextBytes) {
                break;
            }
            used += nextBytes;
            index += chars;
        }
        boolean truncated = index < value.length();
        return new Prefix(truncated ? value.substring(0, index) : value, used, truncated);
    }

    private static int encodedBytes(char value) {
        if (value <= 0x7F) {
            return 1;
        }
        if (value <= 0x7FF) {
            return 2;
        }
        // String#getBytes(UTF_8)会将未配对代理字符替换为单字节问号。
        return Character.isSurrogate(value) ? 1 : 3;
    }

    record Prefix(String value, long bytes, boolean truncated) {
    }
}
