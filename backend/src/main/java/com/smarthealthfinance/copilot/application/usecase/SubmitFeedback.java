package com.smarthealthfinance.copilot.application.usecase;

import com.smarthealthfinance.copilot.application.dto.StoredMessage;
import com.smarthealthfinance.copilot.application.exception.CopilotRequestNotFoundException;
import com.smarthealthfinance.copilot.application.port.ConversationStore;
import com.smarthealthfinance.copilot.application.port.FeedbackStore;
import com.smarthealthfinance.copilot.application.service.CopilotAdmission;
import com.smarthealthfinance.copilot.application.service.CopilotHistory;
import com.smarthealthfinance.copilot.domain.enums.FeedbackRating;
import com.smarthealthfinance.copilot.domain.enums.FeedbackReason;
import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.copilot.domain.repository.AiUsageLedger;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Feedback por pergunta (ADR-0040 §8). Exige só membership (não envia nada ao modelo) e que a pergunta seja da pessoa
 * no Workspace, conferido no usage ledger. Vale com ou sem histórico; sem texto livre; o último envio vale. A
 * intenção e as versões vêm do histórico (se a mensagem existe) ou do ledger; sem intenção conhecida, nulo.
 */
@Service
public class SubmitFeedback {

    private final CopilotAdmission admission;
    private final AiUsageLedger ledger;
    private final ConversationStore conversations;
    private final FeedbackStore feedback;
    private final Clock clock;
    private final MeterRegistry meters;

    public SubmitFeedback(CopilotAdmission admission, AiUsageLedger ledger, ConversationStore conversations,
                          FeedbackStore feedback, Clock clock, MeterRegistry meters) {
        this.admission = admission;
        this.ledger = ledger;
        this.conversations = conversations;
        this.feedback = feedback;
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * @param rating obrigatório
     * @param reason opcional
     * @throws InvalidValueException {@code rating} ausente
     * @throws CopilotRequestNotFoundException a pergunta não é da pessoa no Workspace (ou não existe)
     */
    @Transactional
    public void execute(UUID workspaceId, UUID requestId, FeedbackRating rating, FeedbackReason reason) {
        CopilotAdmission.Member member = admission.requireMember(workspaceId);
        if (rating == null) {
            throw new InvalidValueException("rating", "REQUIRED");
        }
        Map<String, String> ledgerVersions = ledger.findRequestVersions(member.workspaceId(), member.userId(),
                requestId).orElseThrow(CopilotRequestNotFoundException::new);
        Instant now = clock.instant();
        Optional<StoredMessage> message = conversations.findByRequest(member.workspaceId(), member.userId(),
                requestId, now);
        Intent intent = message.map(stored -> stored.result().intent()).orElse(null);
        Map<String, String> versions = new LinkedHashMap<>(ledgerVersions);
        message.ifPresent(stored -> versions.putAll(stored.result().versions()));
        feedback.upsert(new FeedbackStore.Feedback(requestId, member.workspaceId(), member.userId(), rating, reason,
                intent, versions, now, now.plus(CopilotHistory.RETENTION)));
        meters.counter("shf.copilot.api.feedback", "rating", rating.name()).increment();
    }
}
