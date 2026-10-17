package com.smarthealthfinance.insights.infrastructure.persistence.adapter;

import com.smarthealthfinance.health.domain.enums.HealthDimension;
import com.smarthealthfinance.insights.domain.enums.ActionKind;
import com.smarthealthfinance.insights.domain.enums.ContributorKind;
import com.smarthealthfinance.insights.domain.enums.FigureKind;
import com.smarthealthfinance.insights.domain.enums.InsightSource;
import com.smarthealthfinance.insights.domain.enums.LimitationCode;
import com.smarthealthfinance.insights.domain.model.ChangeLine;
import com.smarthealthfinance.insights.domain.model.Contributor;
import com.smarthealthfinance.insights.domain.model.InsightAction;
import com.smarthealthfinance.insights.domain.model.InsightContent;
import com.smarthealthfinance.insights.domain.model.InsightEvidence;
import com.smarthealthfinance.insights.domain.model.InsightRefs;
import com.smarthealthfinance.insights.domain.model.KeyFigure;
import com.smarthealthfinance.insights.domain.model.Limitation;
import com.smarthealthfinance.insights.domain.model.Recommendation;
import com.smarthealthfinance.insights.domain.model.SeriesPoint;
import com.smarthealthfinance.insights.domain.model.SourceRef;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Forma do {@code content} JSONB de {@code insights} (ADR-0022 §5). Datas em ISO e decimais (dinheiro, percentuais,
 * razões) como <b>string</b>, nunca número JSON: a ida e a volta são exatas, e gerar duas vezes o mesmo insight não
 * cria revisão nova.
 */
record InsightContentDocument(String title, String support, String subjectLabel, ChangeLineDoc changeLine,
                              RecommendationDoc recommendation, EvidenceDoc evidence, List<ActionDoc> actions,
                              RefsDoc refs) {

    record ChangeLineDoc(String label, String value, String detail) {}

    record RecommendationDoc(String title, String text) {}

    record EvidenceDoc(List<FigureDoc> figures, List<PointDoc> series, List<ContributorDoc> contributors,
                       String comparison, List<LimitationDoc> limitations, List<SourceDoc> sources) {}

    record FigureDoc(String code, String label, FigureKind kind, String value, String from, String to) {}

    record PointDoc(String month, String value, boolean partial) {}

    record ContributorDoc(ContributorKind kind, String key, String name, String amount, Integer count, String share,
                          String day) {}

    record LimitationDoc(LimitationCode code, String text) {}

    record SourceDoc(InsightSource source, String from, String to) {}

    record ActionDoc(ActionKind kind, String label, boolean primary, boolean available, Map<String, String> params) {}

    record RefsDoc(String categoryCode, String merchantKey, String sourceKey, String transactionId,
                   HealthDimension dimension) {}

    static InsightContentDocument from(InsightContent c) {
        InsightEvidence e = c.evidence();
        InsightRefs r = c.refs();
        return new InsightContentDocument(c.title(), c.support(), c.subjectLabel(),
                c.changeLine() == null ? null
                        : new ChangeLineDoc(c.changeLine().label(), c.changeLine().value(), c.changeLine().detail()),
                c.recommendation() == null ? null
                        : new RecommendationDoc(c.recommendation().title(), c.recommendation().text()),
                new EvidenceDoc(
                        e.figures().stream().map(f -> new FigureDoc(f.code(), f.label(), f.kind(),
                                f.value().toPlainString(), f.from().toString(), f.to().toString())).toList(),
                        e.series().stream().map(p -> new PointDoc(p.month().toString(), p.value().toPlainString(),
                                p.partial())).toList(),
                        e.contributors().stream().map(x -> new ContributorDoc(x.kind(), x.key(), x.name(),
                                x.amount().toPlainString(), x.count(), text(x.share()),
                                x.day() == null ? null : x.day().toString())).toList(),
                        e.comparison(),
                        e.limitations().stream().map(l -> new LimitationDoc(l.code(), l.text())).toList(),
                        e.sources().stream().map(s -> new SourceDoc(s.source(), s.from().toString(),
                                s.to().toString())).toList()),
                c.actions().stream().map(a -> new ActionDoc(a.kind(), a.label(), a.primary(), a.available(),
                        a.params())).toList(),
                new RefsDoc(r.categoryCode(), r.merchantKey(), r.sourceKey(),
                        r.transactionId() == null ? null : r.transactionId().toString(), r.dimension()));
    }

    InsightContent toContent() {
        return new InsightContent(title, support, subjectLabel,
                changeLine == null ? null : new ChangeLine(changeLine.label(), changeLine.value(), changeLine.detail()),
                recommendation == null ? null : new Recommendation(recommendation.title(), recommendation.text()),
                new InsightEvidence(
                        evidence.figures().stream().map(f -> new KeyFigure(f.code(), f.label(), f.kind(),
                                new BigDecimal(f.value()), LocalDate.parse(f.from()), LocalDate.parse(f.to())))
                                .toList(),
                        evidence.series().stream().map(p -> new SeriesPoint(YearMonth.parse(p.month()),
                                new BigDecimal(p.value()), p.partial())).toList(),
                        evidence.contributors().stream().map(x -> new Contributor(x.kind(), x.key(), x.name(),
                                new BigDecimal(x.amount()), x.count(), decimal(x.share()),
                                x.day() == null ? null : LocalDate.parse(x.day()))).toList(),
                        evidence.comparison(),
                        evidence.limitations().stream().map(l -> new Limitation(l.code(), l.text())).toList(),
                        evidence.sources().stream().map(s -> new SourceRef(s.source(), LocalDate.parse(s.from()),
                                LocalDate.parse(s.to()))).toList()),
                actions.stream().map(a -> new InsightAction(a.kind(), a.label(), a.primary(), a.available(),
                        a.params() == null ? Map.of() : a.params())).toList(),
                new InsightRefs(refs.categoryCode(), refs.merchantKey(), refs.sourceKey(),
                        refs.transactionId() == null ? null : UUID.fromString(refs.transactionId()),
                        refs.dimension()));
    }

    private static String text(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static BigDecimal decimal(String text) {
        return text == null ? null : new BigDecimal(text);
    }
}
