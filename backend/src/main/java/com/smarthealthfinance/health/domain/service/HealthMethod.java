package com.smarthealthfinance.health.domain.service;

import com.smarthealthfinance.health.domain.enums.Confidence;
import com.smarthealthfinance.health.domain.enums.HealthDimension;
import com.smarthealthfinance.health.domain.model.HealthWindow;
import com.smarthealthfinance.health.domain.valueobject.ScoreCurve;
import java.math.BigDecimal;
import java.util.List;

/**
 * Conteúdo do "Como calculamos?" (D-HealthMethod, ADR-0017/0019), montado das mesmas constantes que o
 * {@link HealthCalculator} usa: pesos, curvas, mínimos e limiares nunca divergem do cálculo.
 */
public final class HealthMethod {

    /**
     * @param x       entrada da curva (razão 0–1, ou meses de cobertura na Reserve)
     * @param score   nota naquele ponto
     */
    public record CurvePoint(BigDecimal x, BigDecimal score) {}

    /** @param input o que entra no eixo x */
    public record Curve(String name, String input, List<CurvePoint> points) {}

    /**
     * @param applicable {@code false} até existir no produto (Credit e Debt até o M8)
     */
    public record Dimension(HealthDimension dimension, String label, int weight, boolean applicable,
                            String observes, String formula, String minimum, List<Curve> curves) {}

    public record ConfidenceRule(Confidence level, String rule) {}

    public record Description(String algorithmVersion, String about, List<Dimension> dimensions, String overall,
                              String window, List<String> sources, String estimated,
                              List<ConfidenceRule> confidence, String partialMonth, String rounding,
                              List<String> exclusions) {}

    private static final Description DESCRIPTION = new Description(
            HealthCalculator.ALGORITHM_VERSION,
            "O score resume, de 0 a 100, seis dimensões da sua vida financeira. Ele compara você com você mesmo(a) e "
                    + "com referências conhecidas — nunca com outras pessoas.",
            List.of(
                    new Dimension(HealthDimension.CASH_FLOW, HealthDimension.CASH_FLOW.label(),
                            HealthDimension.CASH_FLOW.weight(), true,
                            "Receitas e reembolsos comparados às despesas, mês a mês.",
                            "0,7 × nota da sobra (resultado ÷ receitas) + 0,3 × % de meses com resultado ≥ 0. "
                                    + "Sem receitas, a nota da sobra é 0.",
                            "1 mês fechado com receitas ou despesas.",
                            List.of(curve("Sobra", "resultado ÷ receitas", CashFlowDimension.CURVE))),
                    new Dimension(HealthDimension.SPENDING, HealthDimension.SPENDING.label(),
                            HealthDimension.SPENDING.weight(), true,
                            "Seus gastos comparados à sua própria média dos " + SpendingDimension.AVERAGE_MONTHS
                                    + " meses anteriores, nos mesmos dias do mês.",
                            "0,5 × nota da variação total + 0,5 × nota do excesso por categoria (soma do que cada "
                                    + "categoria passou da própria média ÷ média total).",
                            "1 mês anterior com despesas para comparar.",
                            List.of(curve("Variação", "despesa ÷ média − 1", SpendingDimension.VARIATION_CURVE),
                                    curve("Excesso por categoria", "excesso ÷ média total",
                                            SpendingDimension.EXCESS_CURVE))),
                    new Dimension(HealthDimension.CREDIT, HealthDimension.CREDIT.label(),
                            HealthDimension.CREDIT.weight(), false,
                            "Utilização do limite e pagamento das faturas.",
                            "Disponível quando houver cartões.", "—", List.of()),
                    new Dimension(HealthDimension.DEBT, HealthDimension.DEBT.label(), HealthDimension.DEBT.weight(),
                            false, "Parcelamentos e compromissos futuros.",
                            "Disponível quando houver parcelamentos.", "—", List.of()),
                    new Dimension(HealthDimension.RESERVE, HealthDimension.RESERVE.label(),
                            HealthDimension.RESERVE.weight(), true,
                            "Quantos meses de despesas sua reserva cobre (referência: "
                                    + ReserveDimension.REFERENCE_MONTHS + " meses).",
                            "Saldo das contas poupança ativas (mesmo fora do saldo total) ÷ média mensal de despesas "
                                    + "dos meses fechados.",
                            "Uma conta poupança ativa e 1 mês fechado com despesas.",
                            List.of(curve("Cobertura", "meses de despesas", ReserveDimension.CURVE))),
                    new Dimension(HealthDimension.CONSISTENCY, HealthDimension.CONSISTENCY.label(),
                            HealthDimension.CONSISTENCY.weight(), true,
                            "Regularidade de receitas e despesas ao longo dos meses.",
                            "0,7 × % de meses com receita e despesa a até ±15% das medianas + 0,3 × % da receita "
                                    + "vinda de fontes recorrentes (recebidas em 3 ou mais meses).",
                            ConsistencyDimension.MIN_MONTHS + " meses fechados.", List.of())),
            "Média ponderada das notas das dimensões disponíveis (pesos acima). Dimensões sem dados ou ainda não "
                    + "disponíveis ficam de fora e os pesos das demais são redistribuídos; a cobertura mostra quantas "
                    + "entraram. Precisa de Fluxo de caixa e de mais uma dimensão.",
            "Até os " + HealthWindow.MAX_MONTHS + " últimos meses fechados; o mês em andamento entra só em Gastos.",
            List.of("Lançamentos manuais", "Extratos CSV", "Extratos OFX"),
            "Com menos de " + HealthCalculator.ESTIMATED_BELOW_MONTHS + " meses fechados, o score é estimado.",
            List.of(new ConfidenceRule(Confidence.HIGH, HealthCalculator.HIGH_CONFIDENCE_MONTHS
                            + " meses fechados e até " + HealthCalculator.HIGH_CONFIDENCE_UNCATEGORIZED
                            + "% do gasto sem categoria."),
                    new ConfidenceRule(Confidence.MEDIUM, HealthCalculator.MEDIUM_CONFIDENCE_MONTHS
                            + " meses fechados e até " + HealthCalculator.MEDIUM_CONFIDENCE_UNCATEGORIZED
                            + "% do gasto sem categoria."),
                    new ConfidenceRule(Confidence.LOW, "Os demais casos.")),
            "O mês corrente é parcial e é comparado com os mesmos dias dos meses anteriores.",
            "Notas inteiras de 0 a 100; o score é calculado a partir das notas exibidas.",
            List.of("Investimentos", "Seguros", "Empréstimos complexos", "Cartões e parcelamentos (por enquanto)",
                    "Lançamentos pendentes", "Transferências entre suas contas", "Ajustes de saldo",
                    "Contas arquivadas"));

    private HealthMethod() {
    }

    public static Description describe() {
        return DESCRIPTION;
    }


    private static Curve curve(String name, String input, ScoreCurve curve) {
        return new Curve(name, input, curve.points().stream().map(p -> new CurvePoint(p.x(), p.y())).toList());
    }
}
