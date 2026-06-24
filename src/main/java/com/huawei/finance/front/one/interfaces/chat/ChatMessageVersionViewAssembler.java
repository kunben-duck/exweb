package com.huawei.finance.front.one.interfaces.chat;

import com.huawei.finance.front.one.domain.chat.ChatMessage;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatMessageVersionInfoDto;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatMessageVersionItemDto;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 为历史消息 DTO 装配版本游标信息。
 *
 * <p>版本游标只依赖当前会话的可见 user/assistant 消息树。它不会读取事件表，也不会修改
 * {@code current_leaf_message_id}；前端真正选择版本后仍通过 path 接口持久化当前 leaf。</p>
 */
@Component
public class ChatMessageVersionViewAssembler {
    private static final String ROOT_PARENT_KEY = "__ROOT__";
    private static final Comparator<ChatMessage> MESSAGE_ORDER = Comparator
            .comparing(ChatMessage::nodeOrder, Comparator.nullsLast(Long::compareTo))
            .thenComparing(ChatMessage::createdAt, Comparator.nullsLast(Instant::compareTo))
            .thenComparing(ChatMessage::id, Comparator.nullsLast(String::compareTo));
    private static final Comparator<ChatMessage> DEEPEST_LATEST_ORDER = Comparator
            .comparing(ChatMessage::treeDepth, Comparator.nullsLast(Integer::compareTo))
            .thenComparing(ChatMessage::nodeOrder, Comparator.nullsLast(Long::compareTo))
            .thenComparing(ChatMessage::createdAt, Comparator.nullsLast(Instant::compareTo))
            .thenComparing(ChatMessage::id, Comparator.nullsLast(String::compareTo));

    /**
     * 基于 active path 和会话完整可见树，生成 path 中每条消息的版本摘要。
     *
     * @param activePath 当前接口返回的可见路径消息。
     * @param sessionMessages 当前会话全部可见 user/assistant 消息节点。
     * @return messageId 到版本摘要的映射；没有可切换版本的消息不会出现在 Map 中。
     */
    public Map<String, ChatMessageVersionInfoDto> assemble(
            List<ChatMessage> activePath, List<ChatMessage> sessionMessages) {
        if (activePath == null || activePath.isEmpty() || sessionMessages == null || sessionMessages.isEmpty()) {
            return Map.of();
        }
        List<ChatMessage> orderedMessages = sessionMessages.stream()
                .filter(Objects::nonNull)
                .sorted(MESSAGE_ORDER)
                .toList();
        if (orderedMessages.isEmpty()) {
            return Map.of();
        }

        Map<String, List<ChatMessage>> childrenByParent = orderedMessages.stream()
                .filter(message -> message.parentMessageId() != null)
                .collect(Collectors.groupingBy(ChatMessage::parentMessageId, LinkedHashMap::new, Collectors.toList()));
        childrenByParent.replaceAll((ignored, children) -> children.stream().sorted(MESSAGE_ORDER).toList());

        Map<SiblingGroupKey, List<ChatMessage>> siblingsByGroup = orderedMessages.stream()
                .collect(Collectors.groupingBy(this::siblingGroupKey, LinkedHashMap::new, Collectors.toList()));
        siblingsByGroup.replaceAll((ignored, siblings) -> siblings.stream().sorted(MESSAGE_ORDER).toList());

        Map<String, ChatMessageVersionInfoDto> result = new LinkedHashMap<>();
        for (ChatMessage message : activePath) {
            if (message == null) {
                continue;
            }
            List<ChatMessage> siblings = siblingsByGroup.getOrDefault(siblingGroupKey(message), List.of());
            if (siblings.size() <= 1) {
                continue;
            }
            result.put(message.id(), toVersionInfo(message, siblings, childrenByParent));
        }
        return result;
    }

    private ChatMessageVersionInfoDto toVersionInfo(
            ChatMessage current, List<ChatMessage> siblings, Map<String, List<ChatMessage>> childrenByParent) {
        List<ChatMessageVersionItemDto> variants = new ArrayList<>();
        int currentIndex = 1;
        for (int index = 0; index < siblings.size(); index++) {
            ChatMessage candidate = siblings.get(index);
            boolean selected = candidate.id().equals(current.id());
            if (selected) {
                currentIndex = index + 1;
            }
            variants.add(new ChatMessageVersionItemDto(
                    candidate.id(),
                    index + 1,
                    selected,
                    switchLeafMessageId(candidate, childrenByParent),
                    candidate.locked(),
                    candidate.originType(),
                    candidate.editedFromMessageId(),
                    candidate.regeneratedFromMessageId(),
                    candidate.createdAt()
            ));
        }
        return new ChatMessageVersionInfoDto(current.role(), current.id(), currentIndex, siblings.size(), variants);
    }

    private String switchLeafMessageId(ChatMessage candidate, Map<String, List<ChatMessage>> childrenByParent) {
        if (candidate == null) {
            return null;
        }
        if ("user".equalsIgnoreCase(candidate.role())) {
            return deepestAssistant(candidate, childrenByParent)
                    .map(ChatMessage::id)
                    .orElse(candidate.id());
        }
        return deepestVisibleLeaf(candidate, childrenByParent)
                .map(ChatMessage::id)
                .orElse(candidate.id());
    }

    private Optional<ChatMessage> deepestAssistant(ChatMessage root, Map<String, List<ChatMessage>> childrenByParent) {
        return descendants(root, childrenByParent).stream()
                .filter(message -> "assistant".equalsIgnoreCase(message.role()))
                .max(DEEPEST_LATEST_ORDER);
    }

    private Optional<ChatMessage> deepestVisibleLeaf(ChatMessage root, Map<String, List<ChatMessage>> childrenByParent) {
        return descendantsIncludingSelf(root, childrenByParent).stream()
                .filter(message -> childrenByParent.getOrDefault(message.id(), List.of()).isEmpty())
                .max(DEEPEST_LATEST_ORDER);
    }

    private List<ChatMessage> descendants(ChatMessage root, Map<String, List<ChatMessage>> childrenByParent) {
        List<ChatMessage> messages = descendantsIncludingSelf(root, childrenByParent);
        return messages.stream().filter(message -> !message.id().equals(root.id())).toList();
    }

    private List<ChatMessage> descendantsIncludingSelf(ChatMessage root, Map<String, List<ChatMessage>> childrenByParent) {
        List<ChatMessage> result = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        ArrayDeque<ChatMessage> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            ChatMessage current = stack.pop();
            if (current.id() != null && !visited.add(current.id())) {
                continue;
            }
            result.add(current);
            List<ChatMessage> children = childrenByParent.getOrDefault(current.id(), List.of());
            for (int index = children.size() - 1; index >= 0; index--) {
                stack.push(children.get(index));
            }
        }
        return result;
    }

    private SiblingGroupKey siblingGroupKey(ChatMessage message) {
        return new SiblingGroupKey(parentKey(message.parentMessageId()), normalizeRole(message.role()));
    }

    private String parentKey(String parentMessageId) {
        return parentMessageId == null ? ROOT_PARENT_KEY : parentMessageId;
    }

    private String normalizeRole(String role) {
        return role == null ? "" : role.toLowerCase();
    }

    private record SiblingGroupKey(String parentMessageId, String role) {}
}
