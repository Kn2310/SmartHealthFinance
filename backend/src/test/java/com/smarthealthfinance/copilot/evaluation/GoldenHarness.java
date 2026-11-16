package com.smarthealthfinance.copilot.evaluation;

import com.smarthealthfinance.copilot.application.InMemoryAiUsageLedger;
import com.smarthealthfinance.copilot.application.dto.AskCommand;
import com.smarthealthfinance.copilot.application.dto.CopilotResult;
import com.smarthealthfinance.copilot.application.dto.PreviousQuestion;
import com.smarthealthfinance.copilot.application.exception.ContextNotFoundException;
import com.smarthealthfinance.copilot.application.exception.TargetNotFoundException;
import com.smarthealthfinance.copilot.application.port.AiGateway;
import com.smarthealthfinance.copilot.application.port.EntryContextResolver;
import com.smarthealthfinance.copilot.application.port.FinancialTool;
import com.smarthealthfinance.copilot.application.port.RateLimiter;
import com.smarthealthfinance.copilot.application.port.RequestCatalog;
import com.smarthealthfinance.copilot.application.service.AiUsageRecorder;
import com.smarthealthfinance.copilot.application.service.CopilotAdmission;
import com.smarthealthfinance.copilot.application.service.CopilotPipeline;
import com.smarthealthfinance.copilot.application.service.QuotaUsage;
import com.smarthealthfinance.copilot.application.usecase.AskCopilot;
import com.smarthealthfinance.copilot.domain.enums.ActionKind;
import com.smarthealthfinance.copilot.domain.enums.DataFreshness;
import com.smarthealthfinance.copilot.domain.enums.Intent;
import com.smarthealthfinance.copilot.domain.enums.PseudonymKind;
import com.smarthealthfinance.copilot.domain.enums.ValueKind;
import com.smarthealthfinance.copilot.domain.enums.ValueUnit;
import com.smarthealthfinance.copilot.domain.model.AiUsage;
import com.smarthealthfinance.copilot.domain.model.ContextValue;
import com.smarthealthfinance.copilot.domain.model.CopilotLimits;
import com.smarthealthfinance.copilot.domain.model.DataQuality;
import com.smarthealthfinance.copilot.domain.model.EntryContext;
import com.smarthealthfinance.copilot.domain.model.OfferedAction;
import com.smarthealthfinance.copilot.domain.model.PipelineSettings;
import com.smarthealthfinance.copilot.domain.model.PricingTable;
import com.smarthealthfinance.copilot.domain.model.Pseudonyms;
import com.smarthealthfinance.copilot.domain.model.SourceRef;
import com.smarthealthfinance.copilot.infrastructure.ai.ClasspathCopilotPrompts;
import com.smarthealthfinance.copilot.infrastructure.ai.JacksonCopilotCodec;
import com.smarthealthfinance.identity.application.InMemoryUserRepository;
import com.smarthealthfinance.identity.application.InMemoryWorkspaceRepository;
import com.smarthealthfinance.identity.application.dto.AuthenticatedIdentity;
import com.smarthealthfinance.identity.application.service.CurrentUserService;
import com.smarthealthfinance.identity.application.service.WorkspaceAccessGuard;
import com.smarthealthfinance.identity.domain.model.User;
import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.shared.domain.InvalidValueException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.smarthealthfinance.identity.IdentityFixtures.activeUser;
import static com.smarthealthfinance.identity.IdentityFixtures.personalWorkspace;

/**
 * Roda um caso do golden set pelo caminho real do Copilot (ADR-0042 §2): {@link AskCopilot} com a admissão de
 * verdade (membership, consentimento, contexto de entrada, rate limit e quota, com fakes em memória), o
 * {@link CopilotPipeline} de verdade com os prompts e esquemas versionados, o codec, o ledger e o recorder. O que os
 * casos controlam é o que está além das portas: o catálogo, o contexto de entrada, o que as tools devolvem e o
 * provedor de IA (roteirizado no CI, Anthropic na avaliação real). Nada vai ao banco nem à rede.
 */
public final class GoldenHarness {

    /** Data de referência de {@code design/specs/09-dados-de-exemplo.md}: terça, 22 de setembro de 2026. */
    public static final Instant NOW = Instant.parse("2026-09-22T15:00:00Z");
    public static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    /** Resultado de rodar um caso: a resposta, ou o erro tipado que a API devolveria, e o que ficou no ledger. */
    public record Outcome(CopilotResult result, String error, List<AiUsage> ledger, Map<String, Long> violations) {
    }

    private GoldenHarness() {
    }

    public static Outcome run(GoldenCase golden, AiGateway gateway, Clock clock, PricingTable pricing) {
        InMemoryUserRepository users = new InMemoryUserRepository();
        InMemoryWorkspaceRepository workspaces = new InMemoryWorkspaceRepository();
        User ana = activeUser("sub-ana");
        Workspace workspace = personalWorkspace(ana);
        users.store(ana);
        workspaces.store(workspace);

        InMemoryAiUsageLedger ledger = new InMemoryAiUsageLedger();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        CopilotLimits limits = new CopilotLimits(true, ZONE, 100_000, 100_000, 1_000_000_000L, 3,
                Duration.ofSeconds(20));
        EntryContextResolver contexts = contexts(golden.fixtures().entry());
        CurrentUserService current = new CurrentUserService(
                () -> new AuthenticatedIdentity(ana.externalIdentity(), ana.email().value(), null, null), users);
        CopilotAdmission admission = new CopilotAdmission(current, new WorkspaceAccessGuard(workspaces),
                (ws, user, purpose) -> true, contexts, (ws, user, capacity, refill) -> RateLimiter.Decision.allowed(),
                new QuotaUsage(ledger, limits, clock), limits, meters);
        CopilotPipeline pipeline = new CopilotPipeline(catalog(golden.fixtures().catalog()), contexts, gateway,
                new AiUsageRecorder(ledger, pricing, clock, meters),
                new JacksonCopilotCodec(JsonMapper.builder().build()), new ClasspathCopilotPrompts(),
                tools(golden.fixtures().tool()), PipelineSettings.DEFAULT, limits, clock, meters);
        AskCopilot ask = new AskCopilot(admission, pipeline, PipelineSettings.DEFAULT, clock);

        AskCommand command = new AskCommand(golden.question(), golden.previousQuestions().stream()
                .map(previous -> new PreviousQuestion(previous.question(), previous.intent())).toList(),
                entryContext(golden.context()));
        CopilotResult result = null;
        String error = null;
        try {
            result = ask.ask(workspace.id().value(), command);
        } catch (ContextNotFoundException e) {
            error = "CONTEXT_NOT_FOUND";
        } catch (InvalidValueException e) {
            error = "INVALID_VALUE:" + e.field() + ":" + e.reason();
        }
        Map<String, Long> violations = new LinkedHashMap<>();
        for (Counter counter : meters.find("shf.copilot.validation.violation").counters()) {
            violations.merge(counter.getId().getTag("code"), (long) counter.count(), Long::sum);
        }
        return new Outcome(result, error, List.copyOf(ledger.rows), violations);
    }

    /** Pseudônimos são dados na ordem do catálogo, por tipo: {@code cartao_1}, {@code cartao_2}, {@code objetivo_1}. */
    static RequestCatalog catalog(GoldenCase.Catalog catalog) {
        GoldenCase.Catalog value = catalog == null ? new GoldenCase.Catalog(null, null, null) : catalog;
        return (workspaceId, pseudonyms) -> {
            List<RequestCatalog.Target> targets = new ArrayList<>();
            for (GoldenCase.Target target : value.targets()) {
                String pseudonym = pseudonyms.assign(PseudonymKind.valueOf(target.kind()), target.id(),
                        target.descriptor(), target.name());
                targets.add(new RequestCatalog.Target(pseudonym, target.description()));
            }
            return new RequestCatalog.Catalog(Currency.getInstance(value.currency() == null ? "BRL"
                    : value.currency()), value.categories(), targets);
        };
    }

    static EntryContextResolver contexts(GoldenCase.Entry entry) {
        return new EntryContextResolver() {
            @Override
            public void verify(UUID workspaceId, EntryContext context) {
                if (entry != null && Boolean.TRUE.equals(entry.verifyFails())) {
                    throw new ContextNotFoundException();
                }
            }

            @Override
            public Resolved resolve(UUID workspaceId, EntryContext context, Pseudonyms pseudonyms) {
                if (entry == null) {
                    throw new IllegalStateException("a case with a context needs fixtures.entry");
                }
                if (Boolean.TRUE.equals(entry.resolveFails())) {
                    throw new TargetNotFoundException();
                }
                entry.assigns().forEach(target -> pseudonyms.assign(PseudonymKind.valueOf(target.kind()), target.id(),
                        target.descriptor(), target.name()));
                Map<PseudonymKind, String> targets = new EnumMap<>(PseudonymKind.class);
                entry.targets().forEach((kind, pseudonym) -> targets.put(PseudonymKind.valueOf(kind), pseudonym));
                GoldenCase.Screen screen = entry.screen();
                return new Resolved(targets, entry.month() == null ? null : YearMonth.parse(entry.month()),
                        new OfferedAction(ActionKind.valueOf(screen.kind()), screen.target(), screen.params()));
            }
        };
    }

    static EntryContext entryContext(GoldenCase.EntryRef ref) {
        if (ref == null) {
            return null;
        }
        return switch (ref.kind()) {
            case "INSIGHT" -> new EntryContext.Insight(UUID.fromString(ref.insightId()));
            case "INVOICE" -> new EntryContext.Invoice(UUID.fromString(ref.cardId()), YearMonth.parse(ref.month()));
            case "INSTALLMENT_PLAN" -> new EntryContext.InstallmentPlan(UUID.fromString(ref.cardId()),
                    UUID.fromString(ref.planId()));
            case "GOAL" -> new EntryContext.Goal(UUID.fromString(ref.goalId()));
            default -> throw new IllegalArgumentException("unknown context kind " + ref.kind());
        };
    }

    /** Uma tool por intenção com dados, todas devolvendo a mesma fixture: uma intenção errada não escapa da conferência. */
    static List<FinancialTool> tools(GoldenCase.Tool tool) {
        List<FinancialTool> tools = new ArrayList<>();
        for (Intent intent : Intent.values()) {
            if (!intent.answeredWithoutData()) {
                tools.add(new FixtureTool(intent, tool));
            }
        }
        return tools;
    }

    private record FixtureTool(Intent intent, GoldenCase.Tool fixture) implements FinancialTool {

        @Override
        public Result collect(Request request) {
            if (fixture == null) {
                return Result.of(Status.NO_DATA);
            }
            if (fixture.failure() != null) {
                throw switch (fixture.failure()) {
                    case "TARGET_NOT_FOUND" -> new TargetNotFoundException();
                    case "INVALID_VALUE" -> new InvalidValueException("month", "FUTURE");
                    default -> throw new IllegalStateException("unknown tool failure " + fixture.failure());
                };
            }
            Status status = Status.valueOf(fixture.status() == null ? "DATA" : fixture.status());
            if (status != Status.DATA) {
                return Result.of(status);
            }
            List<ContextValue> values = fixture.values().stream().map(value -> ContextValue.draft(value.label(),
                    value.value(), ValueUnit.valueOf(value.unit()), ValueKind.valueOf(value.kind()), value.period(),
                    value.source() != null ? value.source() : fixture.source())).toList();
            List<OfferedAction> actions = fixture.actions().stream()
                    .map(offer -> new OfferedAction(ActionKind.valueOf(offer.kind()), offer.target())).toList();
            BreakdownDraft breakdown = fixture.breakdown() == null ? null : new BreakdownDraft(
                    fixture.breakdown().title(), fixture.breakdown().rows().stream()
                    .map(row -> new BreakdownDraft.Row(row.label(), row.valueIndex(), row.detail())).toList(),
                    fixture.breakdown().totalIndex());
            return new Result(Status.DATA, values, quality(fixture.quality()), fixture.sources().stream()
                    .map(source -> new SourceRef(source.tool(), source.version())).toList(), actions, breakdown);
        }

        private static DataQuality quality(GoldenCase.Quality quality) {
            if (quality == null) {
                return new DataQuality(6, false, List.of(), DataFreshness.UP_TO_DATE, Set.of("OFX"));
            }
            return new DataQuality(quality.monthsWithData(), Boolean.TRUE.equals(quality.partialPeriod()),
                    quality.gaps() == null ? List.of() : quality.gaps(),
                    DataFreshness.valueOf(quality.freshness() == null ? "UP_TO_DATE" : quality.freshness()),
                    quality.sources() == null ? Set.of() : Set.copyOf(quality.sources()));
        }
    }

    /** O relógio do CI: fixo na data de referência. */
    public static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
