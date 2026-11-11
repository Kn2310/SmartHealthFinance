package com.smarthealthfinance.copilot.presentation.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.smarthealthfinance.copilot.application.service.SubmitCopilotQuestion;
import com.smarthealthfinance.copilot.application.usecase.GetCopilotUsage;
import com.smarthealthfinance.copilot.domain.enums.Notice;
import com.smarthealthfinance.copilot.domain.model.AnswerSegment;
import com.smarthealthfinance.copilot.domain.model.Breakdown;
import com.smarthealthfinance.copilot.domain.model.DataUsedItem;
import com.smarthealthfinance.copilot.domain.model.RenderedAnswer;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Respostas da API do Copilot (ADR-0040 §3, §8). Nada daqui vai para log: as respostas têm dados financeiros. */
public final class CopilotResponses {

    private CopilotResponses() {
    }

    /** Resposta a uma pergunta; {@code conversationId} e {@code messageId} são nulos sem histórico. */
    @Schema(name = "CopilotAnswer")
    public record CopilotAnswerResponse(
            UUID requestId,
            @Schema(nullable = true) UUID conversationId,
            @Schema(nullable = true) UUID messageId,
            @Schema(allowableValues = {"OK", "SAFE_MODE", "INSUFFICIENT_DATA", "OUT_OF_SCOPE"}) String code,
            @Schema(description = "Intenção classificada (enum intents-v1)", nullable = true) String intent,
            @Schema(description = "A pergunta como foi enviada, normalizada") String question,
            Answer answer,
            @Schema(allowableValues = {"high", "medium", "low"}, nullable = true) String confidence,
            @Schema(allowableValues = {"VALID", "REGENERATED", "SAFE_MODE", "NOT_APPLICABLE"}) String validation,
            @Schema(description = "PARTIAL_PERIOD, ESTIMATED, SIMULATED, SAFE_MODE, UPDATING") List<String> notices,
            @Schema(description = "Versões de prompts, esquemas e algoritmos usados") Map<String, String> versions,
            Instant createdAt) {

        public static CopilotAnswerResponse from(SubmitCopilotQuestion.Outcome outcome) {
            var result = outcome.result();
            RenderedAnswer answer = result.answerValue().orElseThrow();
            return new CopilotAnswerResponse(result.requestId(), null, null, result.code().name(),
                    result.intent() == null ? null : result.intent().name(), outcome.question(), Answer.from(answer),
                    result.confidence() == null ? null : result.confidence().name().toLowerCase(java.util.Locale.ROOT),
                    result.validation().name(), result.notices().stream().map(Notice::name).toList(),
                    result.versions(), outcome.createdAt());
        }

        /** Sem conteúdo: resposta e pergunta não vão para logs. */
        @Override
        public String toString() {
            return "CopilotAnswer[" + requestId + ", " + code + "]";
        }
    }

    /** A resposta renderizada: texto em segmentos tipados, valores resolvidos por ref e o que o sistema montou. */
    @Schema(name = "CopilotAnswerBody")
    public record Answer(
            @Schema(allowableValues = {"analysis", "explanation", "comparison", "simulation", "out_of_scope",
                    "insufficient_data"}) String type,
            List<Segment> segments,
            @Schema(description = "Passos da explicação, cada um como segmentos") List<List<Segment>> explanation,
            List<Value> values,
            @Schema(description = "Quadro montado pelo sistema; nulo quando não se aplica", nullable = true)
            BreakdownBody breakdown,
            List<Action> actions,
            List<DataUsed> dataUsed) {

        static Answer from(RenderedAnswer answer) {
            return new Answer(answer.type().json(), answer.segments().stream().map(Segment::from).toList(),
                    answer.explanationSegments().stream()
                            .map(step -> step.stream().map(Segment::from).toList()).toList(),
                    answer.financialData().stream().map(Value::from).toList(),
                    answer.breakdown() == null ? null : BreakdownBody.from(answer.breakdown()),
                    answer.actions().stream().map(Action::from).toList(),
                    answer.dataUsed().stream().map(DataUsed::from).toList());
        }
    }

    /** Trecho de texto ({@code TEXT}, com {@code text}) ou de valor ({@code VALUE}, com {@code ref}). */
    @Schema(name = "CopilotSegment")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Segment(@Schema(allowableValues = {"TEXT", "VALUE"}) String kind, String text, String ref) {

        static Segment from(AnswerSegment segment) {
            return switch (segment) {
                case AnswerSegment.Text text -> new Segment("TEXT", text.text(), null);
                case AnswerSegment.Value value -> new Segment("VALUE", null, value.ref());
            };
        }
    }

    /** Valor citado, já formatado em pt-BR; {@code kind} diz se é fato, estimado ou simulado. */
    @Schema(name = "CopilotValue")
    public record Value(String ref, String label, String formatted,
                        @Schema(allowableValues = {"FACT", "ESTIMATED", "SIMULATED"}) String kind,
                        @Schema(nullable = true) String period, String source) {

        static Value from(RenderedAnswer.Value value) {
            return new Value(value.ref(), value.label(), value.formatted(), value.kind().name(), value.period(),
                    value.source());
        }
    }

    /** Quadro de decomposição: linhas e total apontam para refs de {@code values}. */
    @Schema(name = "CopilotBreakdown")
    public record BreakdownBody(String title, List<BreakdownRow> rows,
                                @Schema(description = "Nulo quando o quadro não tem total", nullable = true)
                                String totalRef) {

        static BreakdownBody from(Breakdown breakdown) {
            return new BreakdownBody(breakdown.title(),
                    breakdown.rows().stream()
                            .map(row -> new BreakdownRow(row.label(), row.valueRef(), row.detail())).toList(),
                    breakdown.totalRef());
        }
    }

    @Schema(name = "CopilotBreakdownRow")
    public record BreakdownRow(String label, String valueRef, @Schema(nullable = true) String detail) {
    }

    /** Link para uma tela; a rota é montada pela UI. {@code params} traz, por exemplo, o {@code month} da fatura. */
    @Schema(name = "CopilotAction")
    public record Action(
            @Schema(allowableValues = {"OPEN_INVOICE", "OPEN_INSTALLMENTS", "OPEN_HEALTH", "OPEN_INSIGHT",
                    "OPEN_GOAL", "OPEN_ANALYSIS_CATEGORY", "OPEN_FORECAST"}) String kind,
            String label,
            @Schema(description = "Id interno do alvo; nulo para telas do Workspace inteiro", nullable = true)
            String targetId,
            @Schema(nullable = true) String targetName,
            Map<String, String> params) {

        static Action from(RenderedAnswer.Action action) {
            return new Action(action.kind().name(), action.label(), action.targetId(), action.targetName(),
                    action.params());
        }
    }

    /** Uma fonte de dados com rótulo legível, e se o período é parcial. */
    @Schema(name = "CopilotDataUsed")
    public record DataUsed(String label, boolean partial) {

        static DataUsed from(DataUsedItem item) {
            return new DataUsed(item.label(), item.partial());
        }
    }

    /** Uso do Copilot no Workspace; os limites são provisórios até o M12. */
    @Schema(name = "CopilotUsage")
    public record UsageResponse(UsageWindow month, UsageWindow day,
                                @Schema(description = "Meses com dados que o Copilot enxerga; nulo sem dados",
                                        nullable = true) DataScope dataScope,
                                boolean provisional) {

        public static UsageResponse from(GetCopilotUsage.Usage usage) {
            return new UsageResponse(UsageWindow.from(usage.month()), UsageWindow.from(usage.day()),
                    usage.dataScope().map(scope -> new DataScope(scope.from().toString(), scope.to().toString())).orElse(null),
                    usage.provisional());
        }
    }

    @Schema(name = "CopilotUsageWindow")
    public record UsageWindow(long used, long limit, Instant resetsAt) {

        static UsageWindow from(GetCopilotUsage.Window window) {
            return new UsageWindow(window.used(), window.limit(), window.resetsAt());
        }
    }

    @Schema(name = "CopilotDataScope")
    public record DataScope(@Schema(description = "YYYY-MM", example = "2026-01") String from,
                            @Schema(description = "YYYY-MM", example = "2026-10") String to) {
    }
}
