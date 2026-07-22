package com.huawei.it.ex.one.infrastructure.runtime.domainagent;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** 单次 DomainAgent 响应流使用的增量 UTF-8 解码器。 */
final class DomainAgentUtf8StreamDecoder {
    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    private byte[] pendingBytes = new byte[0];

    String decode(DataBuffer buffer) {
        try {
            byte[] incoming = new byte[buffer.readableByteCount()];
            buffer.read(incoming);
            byte[] bytes = combine(pendingBytes, incoming);
            ByteBuffer input = ByteBuffer.wrap(bytes);
            CharBuffer output = CharBuffer.allocate(Math.max(1, bytes.length));
            check(decoder.decode(input, output, false));
            pendingBytes = new byte[input.remaining()];
            input.get(pendingBytes);
            output.flip();
            return output.toString();
        } catch (CharacterCodingException ex) {
            pendingBytes = new byte[0];
            throw DomainAgentProtocolException.invalidUtf8(ex);
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    String finish() {
        try {
            ByteBuffer input = ByteBuffer.wrap(pendingBytes);
            CharBuffer output = CharBuffer.allocate(Math.max(1, pendingBytes.length));
            check(decoder.decode(input, output, true));
            check(decoder.flush(output));
            pendingBytes = new byte[0];
            output.flip();
            return output.toString();
        } catch (CharacterCodingException ex) {
            pendingBytes = new byte[0];
            throw DomainAgentProtocolException.invalidUtf8(ex);
        }
    }

    private void check(CoderResult result) throws CharacterCodingException {
        if (result.isError()) {
            result.throwException();
        }
    }

    private byte[] combine(byte[] left, byte[] right) {
        if (left.length == 0) {
            return right;
        }
        if (right.length == 0) {
            return left;
        }
        byte[] result = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, result, left.length, right.length);
        return result;
    }
}
