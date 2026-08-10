package com.huawei.it.ex.one.infrastructure.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** 历史消息分页游标编码器；游标固定首次请求的 leaf 和下一页起点。 */
final class ChatMessagePageCursorCodec {
    private static final String VERSION = "v1";
    private static final String DIGEST_CONTEXT = "financeex-chat-message-page-cursor-v1";
    private static final int MAX_CURSOR_LENGTH = 8_192;

    String encode(String sessionId, String anchorLeafMessageId, String pageStartMessageId) {
        requireText(sessionId);
        requireText(anchorLeafMessageId);
        requireText(pageStartMessageId);
        String payload = String.join(".", VERSION, segment(sessionId), segment(anchorLeafMessageId),
                segment(pageStartMessageId));
        String raw = payload + "." + digest(payload);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    Cursor decode(String cursor) {
        if (cursor == null || cursor.isBlank() || cursor.length() > MAX_CURSOR_LENGTH) {
            throw invalidCursor();
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\.", -1);
            if (parts.length != 5 || !VERSION.equals(parts[0])) {
                throw invalidCursor();
            }
            String payload = String.join(".", parts[0], parts[1], parts[2], parts[3]);
            if (!MessageDigest.isEqual(
                    digest(payload).getBytes(StandardCharsets.US_ASCII),
                    parts[4].getBytes(StandardCharsets.US_ASCII))) {
                throw invalidCursor();
            }
            String sessionId = decodeSegment(parts[1]);
            String anchorLeafMessageId = decodeSegment(parts[2]);
            String pageStartMessageId = decodeSegment(parts[3]);
            requireText(sessionId);
            requireText(anchorLeafMessageId);
            requireText(pageStartMessageId);
            return new Cursor(sessionId, anchorLeafMessageId, pageStartMessageId);
        } catch (IllegalArgumentException ex) {
            if ("消息分页游标无效".equals(ex.getMessage())) {
                throw ex;
            }
            throw invalidCursor();
        }
    }

    private String segment(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeSegment(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private String digest(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(DIGEST_CONTEXT.getBytes(StandardCharsets.UTF_8));
            byte[] bytes = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 缺少 SHA-256 支持", ex);
        }
    }

    private void requireText(String value) {
        if (value == null || value.isBlank()) {
            throw invalidCursor();
        }
    }

    private IllegalArgumentException invalidCursor() {
        return new IllegalArgumentException("消息分页游标无效");
    }

    record Cursor(String sessionId, String anchorLeafMessageId, String pageStartMessageId) {
    }
}
