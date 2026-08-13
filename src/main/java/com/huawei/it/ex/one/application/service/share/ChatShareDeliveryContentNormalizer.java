package com.huawei.it.ex.one.application.service.share;

import org.springframework.web.util.HtmlUtils;

import java.util.regex.Pattern;

/**
 * 分享发送正文的轻量纯文本规范化器。
 */
final class ChatShareDeliveryContentNormalizer {
    private static final int MAX_INPUT_LENGTH = 8192;
    private static final Pattern HTML_COMMENT = Pattern.compile("(?s)<!--.*?(?:-->|$)");
    private static final Pattern SCRIPT_OR_STYLE_BLOCK = Pattern.compile(
            "(?is)<(script|style)\\b[^>]*>.*?(?:</\\1\\s*>|\\z)");
    private static final Pattern BLOCK_TAG = Pattern.compile(
            "(?is)</?(?:br|p|div|li|ul|ol|tr|td|th|table|thead|tbody|tfoot|h[1-6]|blockquote|"
                    + "section|article|header|footer|main|pre|hr)\\b[^>]*?/?>");
    private static final Pattern HTML_TAG = Pattern.compile(
            "(?is)</?[a-z][a-z0-9:-]*(?:\\s+[^<>]*?)?\\s*/?>");

    private ChatShareDeliveryContentNormalizer() {
    }

    static String normalize(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() > MAX_INPUT_LENGTH) {
            throw new IllegalArgumentException("分享发送content长度不能超过8192");
        }
        if (value.isBlank()) {
            return "";
        }
        String plainText = HtmlUtils.htmlUnescape(value);
        plainText = HTML_COMMENT.matcher(plainText).replaceAll(" ");
        plainText = SCRIPT_OR_STYLE_BLOCK.matcher(plainText).replaceAll(" ");
        plainText = BLOCK_TAG.matcher(plainText).replaceAll(" ");
        plainText = HTML_TAG.matcher(plainText).replaceAll("");
        return truncateByCodePoint(collapseWhitespace(plainText), Math.max(1, maxLength));
    }

    private static String collapseWhitespace(String value) {
        StringBuilder normalized = new StringBuilder(value.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                pendingSpace = normalized.length() > 0;
                continue;
            }
            if (pendingSpace) {
                normalized.append(' ');
                pendingSpace = false;
            }
            normalized.appendCodePoint(codePoint);
        }
        return normalized.toString();
    }

    private static String truncateByCodePoint(String value, int maxLength) {
        if (value.codePointCount(0, value.length()) <= maxLength) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxLength));
    }
}
