package com.huawei.finance.front.one.infrastructure.memory;

import com.huawei.finance.front.one.application.gateway.SummaryRepository;
import com.huawei.finance.front.one.domain.memory.ConversationSummary;
import com.huawei.finance.front.one.infrastructure.memory.mybatis.ConversationSummaryMapper;
import com.huawei.finance.front.one.infrastructure.memory.mybatis.ConversationSummaryRow;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 会话摘要 openGauss 仓储。
 *
 * <p>摘要属于 MemoryContext 的长期压缩层，必须和消息历史一样可持久恢复；
 * 因此使用 fin_ex_conversation_summary_t 作为事实源。</p>
 */
@Repository
public class OpenGaussSummaryRepository implements SummaryRepository {
    private final ConversationSummaryMapper mapper;

    public OpenGaussSummaryRepository(ConversationSummaryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<ConversationSummary> findLatestBySessionId(String sessionId) {
        return Optional.ofNullable(mapper.findLatestBySessionId(sessionId)).map(this::toDomain);
    }

    @Override
    public void save(ConversationSummary summary) {
        int inserted = mapper.insertFromSession(
                summary.id(),
                summary.sessionId(),
                summary.summaryText(),
                summary.messageFromSeq(),
                summary.messageToSeq(),
                summary.createdAt() == null ? Instant.now() : summary.createdAt()
        );
        if (inserted == 0) {
            throw new IllegalStateException("会话摘要无法落库，关联会话不存在: " + summary.sessionId());
        }
    }

    private ConversationSummary toDomain(ConversationSummaryRow row) {
        return new ConversationSummary(
                row.getId(),
                row.getSessionId(),
                row.getSummaryText(),
                row.getMessageFromSeq(),
                row.getMessageToSeq(),
                row.getCreatedAt() == null ? Instant.EPOCH : row.getCreatedAt()
        );
    }
}
