package com.smarthealthfinance.health.domain.service;

import com.smarthealthfinance.health.domain.enums.FactorDirection;
import com.smarthealthfinance.health.domain.enums.NextStepKind;
import com.smarthealthfinance.health.domain.model.DimensionResult;
import com.smarthealthfinance.health.domain.model.Factor;
import com.smarthealthfinance.health.domain.model.HealthInput;
import com.smarthealthfinance.health.domain.model.HealthResult;
import com.smarthealthfinance.health.domain.model.HealthWindow;
import com.smarthealthfinance.health.domain.model.NextStep;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Frase do resumo, fatores e próximos passos (ADR-0017 §7): templates determinísticos com os números calculados.
 * Sem IA e sem ✦.
 */
final class HealthNarrative {

    static final int SUSTAINING_FROM = 80;
    static final int WEIGHING_BELOW = 70;
    static final int SUMMARY_PER_SIDE = 2;
    static final int FACTORS_PER_SIDE = 3;
    static final int MAX_NEXT_STEPS = 3;
    static final BigDecimal CATEGORIZE_ABOVE_PERCENT = BigDecimal.valueOf(10);

    /** Desempate: peso maior primeiro, depois a ordem de exibição. */
    private static final Comparator<DimensionResult> BY_WEIGHT = Comparator
            .comparingInt((DimensionResult d) -> d.dimension().weight()).reversed()
            .thenComparing(DimensionResult::dimension);

    private HealthNarrative() {
    }

    /**
     * "Seu resultado combina 4 de 6 dimensões. Fluxo de caixa (97) e consistência (100) sustentam o score; reserva
     * (69) é o ponto que mais pesa."
     */
    static String summary(List<DimensionResult> available, HealthResult.Coverage coverage) {
        List<DimensionResult> sustaining = available.stream().filter(d -> d.score() >= SUSTAINING_FROM)
                .sorted(Comparator.comparing(DimensionResult::score).reversed().thenComparing(BY_WEIGHT))
                .limit(SUMMARY_PER_SIDE).toList();
        List<DimensionResult> weighing = available.stream().filter(d -> d.score() < WEIGHING_BELOW)
                .sorted(Comparator.comparing(DimensionResult::score).thenComparing(BY_WEIGHT))
                .limit(SUMMARY_PER_SIDE).toList();

        StringBuilder text = new StringBuilder("Seu resultado combina %d de %d dimensões.".formatted(
                coverage.available(), coverage.total()));
        List<String> clauses = new ArrayList<>();
        if (!sustaining.isEmpty()) {
            clauses.add(names(sustaining) + (sustaining.size() == 1 ? " sustenta o score" : " sustentam o score"));
        }
        if (!weighing.isEmpty()) {
            clauses.add(names(weighing) + (weighing.size() == 1 ? " é o ponto que mais pesa"
                    : " são os pontos que mais pesam"));
        }
        if (clauses.isEmpty()) {
            text.append(" Nenhuma dimensão se destaca: todas ficaram entre %d e %d.".formatted(WEIGHING_BELOW,
                    SUSTAINING_FROM - 1));
        }
        else {
            String sentence = String.join("; ", clauses) + ".";
            text.append(' ').append(Character.toUpperCase(sentence.charAt(0))).append(sentence.substring(1));
        }
        return text.toString();
    }

    /**
     * "Pesando": nota &lt; 70 ou ponto de atenção; "Sustentando": nota ≥ 80 sem ponto de atenção. Uma dimensão aparece
     * em um lado só. Ordem: peso × |nota − 70|, desempate pelo peso.
     */
    static List<Factor> factors(List<DimensionResult> available) {
        Comparator<DimensionResult> impact = Comparator
                .comparingInt((DimensionResult d) -> d.dimension().weight() * Math.abs(d.score() - WEIGHING_BELOW))
                .reversed().thenComparing(BY_WEIGHT);
        Stream<Factor> weighing = available.stream()
                .filter(d -> d.score() < WEIGHING_BELOW || d.attention())
                .sorted(impact).limit(FACTORS_PER_SIDE)
                .map(d -> new Factor(d.dimension(), FactorDirection.WEIGHING, d.explanation()));
        Stream<Factor> sustaining = available.stream()
                .filter(d -> d.score() >= SUSTAINING_FROM && !d.attention())
                .sorted(impact).limit(FACTORS_PER_SIDE)
                .map(d -> new Factor(d.dimension(), FactorDirection.SUSTAINING, d.explanation()));
        return Stream.concat(sustaining, weighing).toList();
    }

    static List<NextStep> nextSteps(HealthInput input, HealthWindow window, CashFlowDimension.Assessment cashFlow,
                                    SpendingDimension.Assessment spending, ReserveDimension.Assessment reserve,
                                    BigDecimal uncategorizedPercent) {
        List<NextStep> steps = new ArrayList<>();
        if (reserve.gap() != null) {
            steps.add(new NextStep(NextStepKind.BUILD_RESERVE, "Fortalecer a reserva",
                    "Faltam %s para 6 meses de despesas.".formatted(HealthTexts.money(reserve.gap())), null, null,
                    reserve.gap()));
        }
        if (reserve.noReserveAccount()) {
            steps.add(new NextStep(NextStepKind.ADD_RESERVE_ACCOUNT, "Indique onde fica sua reserva",
                    "Cadastre sua reserva como conta do tipo poupança para medir quantos meses ela cobre.", null,
                    null, null));
        }
        if (spending.hasTopCategory()) {
            String name = SpendingDimension.categoryName(input, spending.topCategory());
            String title = spending.topPercent() == null ? "%s acima da média".formatted(name)
                    : "%s %s acima da média".formatted(name, HealthTexts.percent(spending.topPercent()));
            steps.add(new NextStep(NextStepKind.REVIEW_CATEGORY, title,
                    "+%s em relação à sua média (%s).".formatted(HealthTexts.money(spending.topExcess()),
                            spending.cut()), spending.topCategory(), input.month(), null));
        }
        if (!window.isEmpty() && uncategorizedPercent.compareTo(CATEGORIZE_ABOVE_PERCENT) > 0) {
            steps.add(new NextStep(NextStepKind.CATEGORIZE_TRANSACTIONS, "Categorizar transações",
                    "%s do gasto de %s está sem categoria.".formatted(HealthTexts.percent(uncategorizedPercent),
                            HealthTexts.months(window.months())), null, null, null));
        }
        if (cashFlow.lastNegativeMonth() != null) {
            steps.add(new NextStep(NextStepKind.REVIEW_MONTH,
                    "Veja o que mudou em %s".formatted(HealthTexts.month(cashFlow.lastNegativeMonth())),
                    "Resultado de %s no mês.".formatted(HealthTexts.money(cashFlow.lastNegativeNet())), null,
                    cashFlow.lastNegativeMonth(), null));
        }
        return steps.stream().limit(MAX_NEXT_STEPS).toList();
    }

    private static String names(List<DimensionResult> dimensions) {
        List<String> parts = dimensions.stream()
                .map(d -> "%s (%d)".formatted(d.dimension().label().toLowerCase(Locale.ROOT), d.score()))
                .toList();
        return String.join(" e ", parts);
    }
}
