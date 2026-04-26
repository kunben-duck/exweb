package com.huawei.finance.front.one.infrastructure.agent.agentscope;

import com.huawei.finance.front.one.application.gateway.AgentRunRequest;
import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.chat.ChatResponseMode;
import com.huawei.finance.front.one.domain.chat.ImMessageType;
import com.huawei.finance.front.one.domain.tool.ToolDefinition;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * AgentScope 系统提示词装配器。
 *
 * <p>这里只写入稳定系统规则和运行约束；会话历史与长期记忆交给 AgentScope Memory/LongTermMemory 处理。</p>
 */
@Component
public class AgentScopePromptAssembler {
    public String systemPrompt(AgentRunRequest request) {
        StringBuilder sb = new StringBuilder();
        ImMessageType messageType = request.messageType() == null ? ImMessageType.TEXT : request.messageType();
        ChatResponseMode responseMode = request.responseMode() == null ? ChatResponseMode.BLOCK : request.responseMode();
        List<AttachmentRef> attachments = request.attachments() == null ? List.of() : request.attachments();
        sb.append("你是 FinanceEX 财经领域 Supervisor Agent 的本地执行引擎。\n");
        sb.append("规则：只能使用 ToolGateway 暴露的工具；不得暴露 providerToolId、URL、密钥；高风险操作必须确认。\n");
        sb.append("当前消息类型：").append(messageType.code()).append("\n");
        sb.append("当前响应方式：").append(responseMode.code()).append("\n");
        if (!attachments.isEmpty()) {
            // 附件只写入元信息；具体文件读取应通过文档/对象存储工具完成。
            sb.append("当前附件：\n");
            attachments.forEach(attachment -> sb.append("- ")
                    .append(attachment.name())
                    .append(" (")
                    .append(attachment.contentType())
                    .append(")\n"));
        }
        sb.append("当前可用工具：\n");
        // 只暴露工具 code 和描述，不向模型泄漏 providerToolId、URL 或密钥。
        for (ToolDefinition tool : request.availableTools()) sb.append("- ").append(tool.toolCode()).append(": ").append(tool.description()).append("\n");
        return sb.toString();
    }
}
