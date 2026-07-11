package com.huawei.finance.front.one.application.service.chat;

import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.chat.ChatRun;
import com.huawei.finance.front.one.domain.chat.ChatRunMessagePlan;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原子提交普通 run 的用户消息树变更和 RUNNING 记录。
 *
 * <p>数据库 active-run 唯一索引是最终准入栅栏。并发失败时整个事务回滚，避免留下
 * 没有关联 run 的用户消息、附件关系或 current leaf。</p>
 */
@Service
public class ChatRunAdmissionCommitService {
    private final SessionApplicationService sessionService;
    private final ChatRunApplicationService chatRunService;

    public ChatRunAdmissionCommitService(SessionApplicationService sessionService,
                                         ChatRunApplicationService chatRunService) {
        this.sessionService = sessionService;
        this.chatRunService = chatRunService;
    }

    @Transactional(timeoutString = "${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}")
    public AdmissionResult commit(UserContext user, ChatCommand command, ChatSession session,
                                  String runId, List<AttachmentRef> attachments) {
        ChatRunMessagePlan messagePlan = sessionService.prepareRunMessage(
                user, command, session, runId, attachments);
        ChatRun run = chatRunService.insertRunning(new CreateChatRunContext(
                runId,
                user,
                session.id(),
                null,
                null,
                command.metadata(),
                messagePlan.runMode(),
                messagePlan.parentMessageId(),
                messagePlan.userMessage().id()
        ));
        return new AdmissionResult(messagePlan, run);
    }

    public record AdmissionResult(ChatRunMessagePlan messagePlan, ChatRun run) {
    }
}
