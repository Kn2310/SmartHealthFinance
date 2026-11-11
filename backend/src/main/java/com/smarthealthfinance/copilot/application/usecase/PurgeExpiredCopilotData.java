package com.smarthealthfinance.copilot.application.usecase;

import com.smarthealthfinance.copilot.application.dto.PurgeRound;
import com.smarthealthfinance.copilot.application.port.ConversationStore;
import com.smarthealthfinance.copilot.application.port.FeedbackStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Retenção de 90 dias (ADR-0040 §6, §8): apaga mensagens expiradas, conversas que ficaram vazias e feedback expirado.
 * Rodado por job diário, sem usuário: atravessa Workspaces, em lotes com {@code FOR UPDATE SKIP LOCKED}. Cobre
 * também a janela da revogação (ADR-0040 §6). Métricas e log só com contagens.
 */
@Service
public class PurgeExpiredCopilotData {

    public static final int BATCH_LIMIT = 500;

    private static final Logger log = LoggerFactory.getLogger(PurgeExpiredCopilotData.class);

    private final ConversationStore conversations;
    private final FeedbackStore feedback;
    private final Clock clock;
    private final MeterRegistry meters;

    public PurgeExpiredCopilotData(ConversationStore conversations, FeedbackStore feedback, Clock clock,
                                   MeterRegistry meters) {
        this.conversations = conversations;
        this.feedback = feedback;
        this.clock = clock;
        this.meters = meters;
    }

    /** Uma rodada, uma transação; o job repete enquanto {@link PurgeRound#full} disser que pode haver mais. */
    @Transactional
    public PurgeRound execute() {
        int messages = conversations.purgeExpiredMessages(clock.instant(), BATCH_LIMIT);
        int emptied = conversations.purgeEmptyConversations(BATCH_LIMIT);
        int expiredFeedback = feedback.purgeExpired(clock.instant(), BATCH_LIMIT);
        if (messages > 0) {
            meters.counter("shf.copilot.history.expired_messages").increment(messages);
        }
        if (emptied > 0) {
            meters.counter("shf.copilot.history.deleted", "cause", "RETENTION").increment(emptied);
        }
        if (expiredFeedback > 0) {
            meters.counter("shf.copilot.history.expired_feedback").increment(expiredFeedback);
        }
        if (messages + emptied + expiredFeedback > 0) {
            log.info("Copilot retention purge messages={} conversations={} feedback={}", messages, emptied,
                    expiredFeedback);
        }
        return new PurgeRound(messages, emptied, expiredFeedback);
    }
}
