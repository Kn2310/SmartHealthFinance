package com.smarthealthfinance.copilot.application.service;

import com.smarthealthfinance.copilot.application.dto.Admission;
import com.smarthealthfinance.copilot.application.dto.AskCommand;
import com.smarthealthfinance.copilot.application.dto.CopilotResult;
import com.smarthealthfinance.copilot.application.port.AiGateway;
import com.smarthealthfinance.copilot.application.port.AiRequest;
import com.smarthealthfinance.copilot.application.port.AiResult;
import com.smarthealthfinance.copilot.application.port.CopilotCodec;
import com.smarthealthfinance.copilot.application.exception.TargetNotFoundException;
import com.smarthealthfinance.copilot.application.port.CopilotPrompts;
import com.smarthealthfinance.copilot.application.port.EntryContextResolver;
import com.smarthealthfinance.copilot.application.port.FinancialTool;
import com.smarthealthfinance.copilot.application.port.RequestCatalog;
import com.smarthealthfinance.copilot.domain.enums.AiOutcome;
import com.smarthealthfinance.copilot.domain.enums.AiRequestType;
import com.smarthealthfinance.copilot.domain.enums.AnswerConfidence;
import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.copilot.domain.enums.Notice;
import com.smarthealthfinance.copilot.domain.enums.PseudonymKind;
import com.smarthealthfinance.copilot.domain.enums.ResultCode;
import com.smarthealthfinance.copilot.domain.enums.ValidationStatus;
import com.smarthealthfinance.copilot.domain.model.AnswerDraft;
import com.smarthealthfinance.copilot.domain.model.Breakdown;
import com.smarthealthfinance.copilot.domain.model.CopilotContext;
import com.smarthealthfinance.copilot.domain.model.CopilotLimits;
import com.smarthealthfinance.copilot.domain.model.IntentDecision;
import com.smarthealthfinance.copilot.domain.model.OfferedAction;
import com.smarthealthfinance.copilot.domain.model.PipelineSettings;
import com.smarthealthfinance.copilot.domain.model.Pseudonyms;
import com.smarthealthfinance.copilot.domain.model.RenderedAnswer;
import com.smarthealthfinance.copilot.domain.service.AnswerNotices;
import com.smarthealthfinance.copilot.domain.service.AnswerRenderer;
import com.smarthealthfinance.copilot.domain.service.ClaimValidator;
import com.smarthealthfinance.copilot.domain.service.ConfidencePolicy;
import com.smarthealthfinance.copilot.domain.service.DataUsedLabels;
import com.smarthealthfinance.copilot.domain.service.TemplateAnswers;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Pipeline de uma pergunta já admitida (ADR-0039 §13): Intent → Context Builder → Financial Queries (tools) →
 * Deterministic Calculations (nos casos de uso) → Context Validation → Prompt → resposta → Structured Output Validation
 * → Financial Claim Validation → renderização. Toda chamada ao modelo vai para o ledger, com as versões (§11).
 *
 * <ul>
 *   <li>Intenção sem dados: {@code INSUFFICIENT_DATA}, sem chamar o modelo de resposta (§5).</li>
 *   <li>Resposta reprovada: regenera uma vez com as violações; reprovada de novo, modo seguro (§7.3).</li>
 *   <li>Provedor fora, recusa ou orçamento de tempo esgotado na resposta: modo seguro com os dados já calculados
 *       (§2). Sem dados ainda (falha na intenção): {@code PROVIDER_UNAVAILABLE}.</li>
 * </ul>
 * Logs e métricas levam só ids, códigos e tempos (§3).
 */
@Service
public class CopilotPipeline {

    private static final Logger log = LoggerFactory.getLogger(CopilotPipeline.class);

    private final RequestCatalog catalog;
    private final EntryContextResolver contexts;
    private final AiGateway gateway;
    private final AiUsageRecorder recorder;
    private final CopilotCodec codec;
    private final CopilotPrompts prompts;
    private final Map<Intent, FinancialTool> tools;
    private final PipelineSettings settings;
    private final ZoneId zone;
    private final Clock clock;
    private final MeterRegistry meters;

    public CopilotPipeline(RequestCatalog catalog, EntryContextResolver contexts, AiGateway gateway,
                           AiUsageRecorder recorder, CopilotCodec codec, CopilotPrompts prompts,
                           List<FinancialTool> tools, PipelineSettings settings, CopilotLimits limits, Clock clock,
                           MeterRegistry meters) {
        this.catalog = catalog;
        this.contexts = contexts;
        this.gateway = gateway;
        this.recorder = recorder;
        this.codec = codec;
        this.prompts = prompts;
        this.tools = byIntent(tools);
        this.settings = settings;
        this.zone = limits.zone();
        this.clock = clock;
        this.meters = meters;
    }

    /** Admissão recusada: nada vai ao modelo nem ao ledger. */
    public CopilotResult refused(UUID requestId, Admission admission) {
        ResultCode code = switch (admission.code()) {
            case CONSENT_REQUIRED -> ResultCode.CONSENT_REQUIRED;
            case RATE_LIMITED -> ResultCode.RATE_LIMITED;
            case QUOTA_EXCEEDED -> ResultCode.QUOTA_EXCEEDED;
            case UNAVAILABLE -> ResultCode.PROVIDER_UNAVAILABLE;
            case ADMITTED -> throw new IllegalArgumentException("admitted request is not refused");
        };
        CopilotResult result = new CopilotResult(requestId, code, null, null, null, ValidationStatus.NOT_APPLICABLE,
                admission.retryAfter(), admission.resetsAt(), versions());
        return done(result, admission, clock.instant());
    }

    /** Pergunta sem continuidade nem contexto de entrada. */
    public CopilotResult run(UUID requestId, Admission admission, String question) {
        return run(requestId, admission, AskCommand.of(question));
    }

    /** {@code command} já validado e normalizado por {@code AskCopilot}. */
    public CopilotResult run(UUID requestId, Admission admission, AskCommand command) {
        Instant started = clock.instant();
        Instant deadline = started.plus(settings.budget());
        LocalDate today = LocalDate.ofInstant(started, zone);
        Pseudonyms pseudonyms = new Pseudonyms();
        RequestCatalog.Catalog requestCatalog = catalog.load(admission.workspaceId().value(), pseudonyms);

        AiResult classified = gateway.classify(new AiRequest(prompts.intentPrompt().text(),
                codec.intentInput(command.question(), command.previousQuestions(), today, requestCatalog),
                prompts.intentSchema().text(), deadline));
        record(admission, requestId, AiRequestType.INTENT, classified, prompts.intentPrompt(), prompts.intentSchema());
        if (classified.outcome() == AiOutcome.REFUSED) {
            return answered(requestId, admission, started, ResultCode.OUT_OF_SCOPE, Intent.OUT_OF_SCOPE,
                    TemplateAnswers.outOfScope(), null, ValidationStatus.NOT_APPLICABLE);
        }
        Optional<IntentDecision> decision = classified.output()
                .flatMap(codec::readIntent)
                .flatMap(raw -> IntentResolver.resolve(raw, requestCatalog, pseudonyms, today));
        if (decision.isEmpty()) {
            if (classified.outcome() == AiOutcome.OK) {
                meters.counter("shf.copilot.validation.violation", "code", "INTENT_SCHEMA").increment();
            }
            return answered(requestId, admission, started, ResultCode.PROVIDER_UNAVAILABLE, null, null, null,
                    ValidationStatus.NOT_APPLICABLE);
        }
        Intent intent = decision.get().intent();
        if (intent == Intent.OUT_OF_SCOPE) {
            return answered(requestId, admission, started, ResultCode.OUT_OF_SCOPE, intent,
                    TemplateAnswers.outOfScope(), null, ValidationStatus.NOT_APPLICABLE);
        }

        Optional<EntryContextResolver.Resolved> entry;
        try {
            entry = command.contextValue().map(context -> contexts.resolve(admission.workspaceId().value(), context,
                    pseudonyms));
        } catch (TargetNotFoundException e) {
            // O alvo sumiu entre a admissão e agora (ADR-0040 §9).
            return insufficient(requestId, admission, started, intent);
        }
        if (intent == Intent.UNSUPPORTED_ACTION) {
            // Com contexto, o link da tela do contexto; sem contexto, sem link (adendo ADR-0039 §13).
            List<OfferedAction> links = entry.map(resolved -> List.of(resolved.screen())).orElse(List.of());
            return answered(requestId, admission, started, ResultCode.OUT_OF_SCOPE, intent,
                    TemplateAnswers.unsupportedAction(links, pseudonyms), null, ValidationStatus.NOT_APPLICABLE);
        }

        IntentDecision effective = entry.map(resolved -> withContext(decision.get(), resolved))
                .orElse(decision.get());
        FinancialTool.Result data = collect(admission, effective, pseudonyms, today, requestCatalog);
        CopilotContext context = ContextBuilder.build(intent, today, requestCatalog.currency(), data,
                settings.contextTokenCeiling());
        if (data.status() != FinancialTool.Status.DATA || context.empty()) {
            return insufficient(requestId, admission, started, intent);
        }
        AnswerConfidence confidence = ConfidencePolicy.of(context);
        return answer(requestId, admission, started, deadline, command, context, pseudonyms, confidence);
    }

    private CopilotResult insufficient(UUID requestId, Admission admission, Instant started, Intent intent) {
        return answered(requestId, admission, started, ResultCode.INSUFFICIENT_DATA, intent,
                TemplateAnswers.insufficientData(intent), null, ValidationStatus.NOT_APPLICABLE);
    }

    /**
     * O alvo do contexto de entrada substitui a escolha do modelo de intenção (adendo ADR-0039 §13), só quando o
     * tipo do alvo é o que a intenção usa; a fatura também fixa o mês, só em {@code CARDS}.
     */
    private static IntentDecision withContext(IntentDecision decision, EntryContextResolver.Resolved resolved) {
        PseudonymKind kind = switch (decision.intent()) {
            case CARDS -> PseudonymKind.CARD;
            case GOALS, GOAL_SIMULATION -> PseudonymKind.GOAL;
            case INSIGHTS -> PseudonymKind.INSIGHT;
            default -> null;
        };
        String target = kind == null ? decision.target() : resolved.target(kind).orElse(decision.target());
        YearMonth month = decision.intent() == Intent.CARDS ? resolved.monthValue().orElse(decision.month())
                : decision.month();
        return new IntentDecision(decision.intent(), month, decision.category(), target, decision.scenario());
    }

    private FinancialTool.Result collect(Admission admission, IntentDecision decision, Pseudonyms pseudonyms,
                                         LocalDate today, RequestCatalog.Catalog requestCatalog) {
        FinancialTool tool = tools.get(decision.intent());
        if (tool == null) {
            throw new IllegalStateException("no tool for intent " + decision.intent());
        }
        Timer.Sample sample = Timer.start(meters);
        FinancialTool.Result data;
        try {
            data = tool.collect(new FinancialTool.Request(admission.workspaceId().value(), decision, pseudonyms, today,
                    requestCatalog.categoryNames()));
        } catch (InvalidValueException | TargetNotFoundException e) {
            data = FinancialTool.Result.of(FinancialTool.Status.NO_DATA);
        }
        sample.stop(Timer.builder("shf.copilot.tool").tag("intent", decision.intent().name())
                .tag("status", data.status().name()).register(meters));
        return data;
    }

    /** Resposta, validação e no máximo uma regeneração, dentro do orçamento; senão, modo seguro. */
    private CopilotResult answer(UUID requestId, Admission admission, Instant started, Instant deadline,
                                 AskCommand command, CopilotContext context, Pseudonyms pseudonyms,
                                 AnswerConfidence confidence) {
        List<String> violations = List.of();
        for (AiRequestType attempt : List.of(AiRequestType.ANSWER, AiRequestType.REGENERATE)) {
            if (!worthCalling(deadline)) {
                meters.counter("shf.copilot.budget_exhausted").increment();
                break;
            }
            AiResult result = gateway.answer(new AiRequest(prompts.answerPrompt().text(),
                    codec.answerInput(command.question(), command.previousQuestions(), context, violations),
                    prompts.answerSchema().text(), deadline));
            record(admission, requestId, attempt, result, prompts.answerPrompt(), prompts.answerSchema());
            if (result.outcome() != AiOutcome.OK) {
                break;
            }
            Optional<AnswerDraft> draft = result.output().flatMap(codec::readAnswer);
            violations = draft
                    .map(value -> ClaimValidator.validate(value, context, pseudonyms).stream()
                            .map(ClaimValidator.Violation::code).distinct().toList())
                    .orElse(List.of("SCHEMA"));
            if (violations.isEmpty()) {
                ValidationStatus status = attempt == AiRequestType.ANSWER ? ValidationStatus.VALID
                        : ValidationStatus.REGENERATED;
                RenderedAnswer rendered = enrich(AnswerRenderer.render(draft.orElseThrow(), context, pseudonyms),
                        context, pseudonyms);
                return answered(requestId, admission, started, ResultCode.OK, context.intent(), rendered, confidence,
                        status, AnswerNotices.of(context, status));
            }
            violations.forEach(code -> meters.counter("shf.copilot.validation.violation", "code", code).increment());
        }
        return answered(requestId, admission, started, ResultCode.SAFE_MODE, context.intent(),
                enrich(TemplateAnswers.safeMode(context, pseudonyms), context, pseudonyms), confidence,
                ValidationStatus.SAFE_MODE, AnswerNotices.of(context, ValidationStatus.SAFE_MODE));
    }

    /**
     * Quadro de decomposição e "Dados usados" (ADR-0040 §3): montados pelo sistema a partir do contexto, nunca pelo
     * modelo. Os valores que o quadro cita entram na lista de valores da resposta para a UI resolver por ref.
     */
    private static RenderedAnswer enrich(RenderedAnswer answer, CopilotContext context, Pseudonyms pseudonyms) {
        Breakdown breakdown = context.breakdown();
        List<RenderedAnswer.Value> cited = List.of();
        if (breakdown != null) {
            List<String> refs = new ArrayList<>(breakdown.rows().stream().map(Breakdown.Row::valueRef).toList());
            if (breakdown.totalRef() != null) {
                refs.add(breakdown.totalRef());
            }
            cited = refs.stream().map(context::find).flatMap(Optional::stream)
                    .map(value -> AnswerRenderer.value(value, context, pseudonyms)).toList();
        }
        return answer.withSystemParts(breakdown, cited, DataUsedLabels.of(context));
    }

    /** Se ainda vale a pena chamar o modelo: resta ao menos o mínimo por chamada até o fim do orçamento. */
    private boolean worthCalling(Instant deadline) {
        return Duration.between(clock.instant(), deadline).compareTo(settings.minimumCall()) >= 0;
    }

    private void record(Admission admission, UUID requestId, AiRequestType type, AiResult result,
                        CopilotPrompts.Versioned prompt, CopilotPrompts.Versioned schema) {
        recorder.record(admission.workspaceId(), admission.userId(), requestId, type, result, prompt.version(),
                schema.name());
    }

    private CopilotResult answered(UUID requestId, Admission admission, Instant started, ResultCode code,
                                   Intent intent, RenderedAnswer answer, AnswerConfidence confidence,
                                   ValidationStatus validation) {
        return answered(requestId, admission, started, code, intent, answer, confidence, validation, List.of());
    }

    private CopilotResult answered(UUID requestId, Admission admission, Instant started, ResultCode code,
                                   Intent intent, RenderedAnswer answer, AnswerConfidence confidence,
                                   ValidationStatus validation, List<Notice> notices) {
        return done(new CopilotResult(requestId, code, intent, answer, confidence, validation, null, null,
                versions(), notices), admission, started);
    }

    private CopilotResult done(CopilotResult result, Admission admission, Instant started) {
        Duration latency = Duration.between(started, clock.instant());
        meters.counter("shf.copilot.pipeline", "code", result.code().name(),
                "intent", result.intent() == null ? "NONE" : result.intent().name()).increment();
        if (result.validation() != ValidationStatus.NOT_APPLICABLE) {
            meters.counter("shf.copilot.validation", "status", result.validation().name()).increment();
        }
        Timer.builder("shf.copilot.pipeline.latency").tag("code", result.code().name()).register(meters)
                .record(latency);
        log.info("Copilot answered requestId={} workspaceId={} code={} intent={} validation={} latencyMs={}",
                result.requestId(), admission.workspaceId(), result.code(), result.intent(), result.validation(),
                latency.toMillis());
        return result;
    }

    /** Versões de prompts, esquemas e algoritmos (§11), no resultado de toda pergunta. */
    private Map<String, String> versions() {
        Map<String, String> versions = new LinkedHashMap<>();
        versions.put("intents", Intent.VERSION);
        versions.put("intentPrompt", prompts.intentPrompt().version());
        versions.put("intentSchema", prompts.intentSchema().name());
        versions.put("answerPrompt", prompts.answerPrompt().version());
        versions.put("answerSchema", prompts.answerSchema().name());
        versions.put("context", CopilotContext.VERSION);
        versions.put("claims", ClaimValidator.VERSION);
        versions.put("confidence", ConfidencePolicy.VERSION);
        return versions;
    }

    private static Map<Intent, FinancialTool> byIntent(List<FinancialTool> tools) {
        Map<Intent, FinancialTool> map = new EnumMap<>(Intent.class);
        for (FinancialTool tool : tools) {
            if (map.put(tool.intent(), tool) != null) {
                throw new IllegalStateException("two tools for intent " + tool.intent());
            }
        }
        return map;
    }
}
