package com.smarthealthfinance.copilot.application.usecase;

import com.smarthealthfinance.copilot.application.dto.Admission;
import com.smarthealthfinance.copilot.application.dto.AskCommand;
import com.smarthealthfinance.copilot.application.dto.CopilotResult;
import com.smarthealthfinance.copilot.application.service.CopilotAdmission;
import com.smarthealthfinance.copilot.application.service.CopilotPipeline;
import com.smarthealthfinance.copilot.domain.model.PipelineSettings;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import com.smarthealthfinance.shared.domain.UuidV7;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pergunta ao Financial Copilot (ADR-0039 §13, ADR-0040 §4): o caso de uso que a API de conversa chama.
 *
 * <p>Auth → Workspace → consentimento → contexto de entrada → rate limit → quota ({@link CopilotAdmission}) → validação
 * da pergunta → {@link CopilotPipeline}. A idempotência fica antes, na API ({@code SubmitCopilotQuestion}). Nunca roda
 * dentro de uma transação de banco: as chamadas ao provedor ficam fora de qualquer transação; cada tool e cada
 * registro no ledger têm a sua.
 */
@Service
public class AskCopilot {

    /** Perguntas anteriores que vão ao modelo (ADR-0040 §7). */
    public static final int MAX_PREVIOUS_QUESTIONS = 3;

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

    /** Pergunta sem continuidade nem contexto de entrada. */
    public CopilotResult ask(UUID workspaceId, String question) {
        return ask(workspaceId, AskCommand.of(question));
    }

    /**
     * @throws InvalidValueException {@code question} {@code REQUIRED} ou {@code TOO_LONG}; {@code previousQuestions}
     *         {@code TOO_MANY}, {@code REQUIRED} ou {@code TOO_LONG}; só depois da autorização (ADR-0003)
     * @throws com.smarthealthfinance.copilot.application.exception.ContextNotFoundException alvo do contexto fora do
     *         Workspace
     */
    public CopilotResult ask(UUID workspaceId, AskCommand command) {
        Admission decision = admission.admit(workspaceId, command.context());
        UUID requestId = UuidV7.generate(clock.instant());
        if (!decision.admitted()) {
            return pipeline.refused(requestId, decision);
        }
        String text = command.question() == null ? "" : command.question().strip();
        if (text.isEmpty()) {
            throw new InvalidValueException("question", "REQUIRED");
        }
        if (text.length() > settings.maxQuestionChars()) {
            throw new InvalidValueException("question", "TOO_LONG");
        }
        return pipeline.run(requestId, decision,
                new AskCommand(text, previous(command.previousQuestions()), command.context()));
    }

    private List<String> previous(List<String> questions) {
        if (questions.size() > MAX_PREVIOUS_QUESTIONS) {
            throw new InvalidValueException("previousQuestions", "TOO_MANY");
        }
        List<String> normalized = new ArrayList<>();
        for (String question : questions) {
            String text = question == null ? "" : question.strip();
            if (text.isEmpty()) {
                throw new InvalidValueException("previousQuestions", "REQUIRED");
            }
            if (text.length() > settings.maxQuestionChars()) {
                throw new InvalidValueException("previousQuestions", "TOO_LONG");
            }
            normalized.add(text);
        }
        return normalized;
    }
}
