package com.smarthealthfinance.copilot.application.usecase;

import com.smarthealthfinance.copilot.application.dto.Admission;
import com.smarthealthfinance.copilot.application.dto.CopilotResult;
import com.smarthealthfinance.copilot.application.service.CopilotAdmission;
import com.smarthealthfinance.copilot.application.service.CopilotPipeline;
import com.smarthealthfinance.copilot.domain.model.PipelineSettings;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import com.smarthealthfinance.shared.domain.UuidV7;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.UUID;

/**
 * Pergunta ao Financial Copilot (ADR-0039 §13): o caso de uso interno que a API de conversa da Sessão 8.2 chama.
 *
 * <p>Auth → Workspace → consentimento → rate limit → quota ({@link CopilotAdmission}) → validação da pergunta →
 * {@link CopilotPipeline}. Nunca roda dentro de uma transação de banco: as chamadas ao provedor ficam fora de qualquer
 * transação; cada tool e cada registro no ledger têm a sua.
 */
@Service
public class AskCopilot {

    private final CopilotAdmission admission;
    private final CopilotPipeline pipeline;
    private final PipelineSettings settings;
    private final Clock clock;

    public AskCopilot(CopilotAdmission admission, CopilotPipeline pipeline, PipelineSettings settings, Clock clock) {
        this.admission = admission;
        this.pipeline = pipeline;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * @throws InvalidValueException {@code question} {@code REQUIRED} ou {@code TOO_LONG}, só depois da autorização
     *                               (ADR-0003)
     */
    public CopilotResult ask(UUID workspaceId, String question) {
        Admission decision = admission.admit(workspaceId);
        UUID requestId = UuidV7.generate(clock.instant());
        if (!decision.admitted()) {
            return pipeline.refused(requestId, decision);
        }
        String text = question == null ? "" : question.strip();
        if (text.isEmpty()) {
            throw new InvalidValueException("question", "REQUIRED");
        }
        if (text.length() > settings.maxQuestionChars()) {
            throw new InvalidValueException("question", "TOO_LONG");
        }
        return pipeline.run(requestId, decision, text);
    }
}
