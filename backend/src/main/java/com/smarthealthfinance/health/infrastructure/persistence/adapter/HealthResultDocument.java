package com.smarthealthfinance.health.infrastructure.persistence.adapter;

import com.smarthealthfinance.health.domain.enums.Confidence;
import com.smarthealthfinance.health.domain.enums.DimensionStatus;
import com.smarthealthfinance.health.domain.enums.EvidenceKind;
import com.smarthealthfinance.health.domain.enums.EvidenceSource;
import com.smarthealthfinance.health.domain.enums.FactorDirection;
import com.smarthealthfinance.health.domain.enums.HealthDimension;
import com.smarthealthfinance.health.domain.enums.HealthState;
import com.smarthealthfinance.health.domain.enums.InsufficientReason;
import com.smarthealthfinance.health.domain.enums.NextStepKind;
import com.smarthealthfinance.health.domain.model.DimensionResult;
import com.smarthealthfinance.health.domain.model.Evidence;
import com.smarthealthfinance.health.domain.model.Factor;
import com.smarthealthfinance.health.domain.model.HealthResult;
import com.smarthealthfinance.health.domain.model.NextStep;
import com.smarthealthfinance.shared.domain.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

/**
 * Forma do {@code result} JSONB de {@code health_snapshots} (ADR-0018 §4). Datas em ISO e decimais (dinheiro,
 * percentuais, meses de cobertura) como <b>string</b>, nunca número JSON: a ida e a volta são exatas.
 * {@code activeCards} e o {@code cardId} dos passos chegaram com o {@code health-v2}: nulos nos documentos da v1.
 */
record HealthResultDocument(String algorithmVersion, String month, String asOf, boolean partial, String windowFrom,
                            String windowTo, int monthsUsed, HealthState state, Integer score, Integer variation,
                            boolean estimated, Confidence confidence, String uncategorizedShare, CoverageDoc coverage,
                            int activeAccounts, String summary, List<DimensionDoc> dimensions,
                            List<FactorDoc> factors, List<NextStepDoc> nextSteps, Integer activeCards) {

    record CoverageDoc(int available, int total, int weightPercent) {}

    record DimensionDoc(HealthDimension dimension, DimensionStatus status, Integer score, InsufficientReason reason,
                        String explanation, boolean attention, List<EvidenceDoc> evidence) {}

    record EvidenceDoc(String code, String label, EvidenceKind kind, String value, String from, String to,
                       EvidenceSource source) {}

    record FactorDoc(HealthDimension dimension, FactorDirection direction, String text) {}

    record NextStepDoc(NextStepKind kind, String text, String evidence, String categoryCode, String month,
                       String amount, String currency, String cardId) {}

    static HealthResultDocument from(HealthResult r) {
        return new HealthResultDocument(r.algorithmVersion(), r.month().toString(), r.asOf().toString(), r.partial(),
                text(r.windowFrom()), text(r.windowTo()), r.monthsUsed(), r.state(), r.score(), r.variation(),
                r.estimated(), r.confidence(), r.uncategorizedShare().toPlainString(),
                new CoverageDoc(r.coverage().available(), r.coverage().total(), r.coverage().weightPercent()),
                r.activeAccounts(), r.summary(),
                r.dimensions().stream().map(HealthResultDocument::dimension).toList(),
                r.factors().stream().map(f -> new FactorDoc(f.dimension(), f.direction(), f.text())).toList(),
                r.nextSteps().stream().map(HealthResultDocument::step).toList(), r.activeCards());
    }

    HealthResult toResult() {
        return new HealthResult(algorithmVersion, YearMonth.parse(month), LocalDate.parse(asOf), partial,
                date(windowFrom), date(windowTo), monthsUsed, state, score, variation, estimated, confidence,
                new BigDecimal(uncategorizedShare),
                new HealthResult.Coverage(coverage.available(), coverage.total(), coverage.weightPercent()),
                activeAccounts, summary,
                dimensions.stream().map(d -> new DimensionResult(d.dimension(), d.status(), d.score(), d.reason(),
                        d.explanation(), d.attention(), d.evidence().stream()
                        .map(e -> new Evidence(e.code(), e.label(), e.kind(), new BigDecimal(e.value()),
                                LocalDate.parse(e.from()), LocalDate.parse(e.to()), e.source()))
                        .toList())).toList(),
                factors.stream().map(f -> new Factor(f.dimension(), f.direction(), f.text())).toList(),
                nextSteps.stream().map(s -> new NextStep(s.kind(), s.text(), s.evidence(), s.categoryCode(),
                        s.month() == null ? null : YearMonth.parse(s.month()),
                        s.amount() == null ? null : Money.of(s.amount(), Currency.getInstance(s.currency())),
                        s.cardId() == null ? null : UUID.fromString(s.cardId())))
                        .toList(),
                activeCards == null ? 0 : activeCards);
    }

    private static DimensionDoc dimension(DimensionResult d) {
        return new DimensionDoc(d.dimension(), d.status(), d.score(), d.reason(), d.explanation(), d.attention(),
                d.evidence().stream().map(e -> new EvidenceDoc(e.code(), e.label(), e.kind(),
                        e.value().toPlainString(), e.from().toString(), e.to().toString(), e.source())).toList());
    }

    private static NextStepDoc step(NextStep s) {
        return new NextStepDoc(s.kind(), s.text(), s.evidence(), s.categoryCode(),
                s.month() == null ? null : s.month().toString(),
                s.amount() == null ? null : s.amount().amount().toPlainString(),
                s.amount() == null ? null : s.amount().currency().getCurrencyCode(),
                s.cardId() == null ? null : s.cardId().toString());
    }

    private static String text(LocalDate date) {
        return date == null ? null : date.toString();
    }

    private static LocalDate date(String text) {
        return text == null ? null : LocalDate.parse(text);
    }
}
