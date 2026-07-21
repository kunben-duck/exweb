package com.huawei.it.ex.one.runtime.infrastructure.relay;

import java.util.ArrayList;
import java.util.List;

/** Splits the existing SSE-like Relay response without interpreting business frames. */
final class RelayFrameSplitter {

    List<String> split(String text) {
        SplitState state = new SplitState();
        for (String line : text.split("\\R")) {
            state.accept(line.trim());
        }
        return state.finish(text);
    }

    private static final class SplitState {
        private final List<String> frames = new ArrayList<>();
        private final StringBuilder currentSseData = new StringBuilder();
        private boolean sawSseLine;

        private void accept(String trimmed) {
            if (trimmed.isEmpty()) {
                flush();
                return;
            }
            if (trimmed.startsWith(":")) {
                sawSseLine = true;
                return;
            }
            if (isSseMetadata(trimmed)) {
                sawSseLine = true;
                return;
            }
            if (trimmed.startsWith("data:")) {
                sawSseLine = true;
                appendData(trimmed.substring("data:".length()).trim());
                return;
            }
            if (sawSseLine) {
                flush();
                frames.add(trimmed);
            }
        }

        private boolean isSseMetadata(String value) {
            return value.startsWith("event:") || value.startsWith("id:") || value.startsWith("retry:");
        }

        private void appendData(String value) {
            if (value.isEmpty()) {
                return;
            }
            if (!currentSseData.isEmpty()) {
                currentSseData.append('\n');
            }
            currentSseData.append(value);
        }

        private List<String> finish(String originalText) {
            flush();
            if (!sawSseLine) {
                frames.add(originalText);
            }
            return frames;
        }

        private void flush() {
            if (!currentSseData.isEmpty()) {
                frames.add(currentSseData.toString());
                currentSseData.setLength(0);
            }
        }
    }
}
