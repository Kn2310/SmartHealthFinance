package com.smarthealthfinance.insights.domain.service;

import com.smarthealthfinance.cards.domain.enums.InvoiceItemKind;
import com.smarthealthfinance.cards.domain.enums.InvoiceStatus;
import com.smarthealthfinance.cards.domain.valueobject.CardPosition;
import com.smarthealthfinance.cards.domain.valueobject.CardPosition.InvoiceLine;
import com.smarthealthfinance.cards.domain.valueobject.InvoiceStatement;
import com.smarthealthfinance.health.domain.enums.Confidence;
import com.smarthealthfinance.health.domain.enums.HealthDimension;
import com.smarthealthfinance.insights.domain.enums.ActionKind;
import com.smarthealthfinance.insights.domain.enums.ContributorKind;
import com.smarthealthfinance.insights.domain.enums.FigureKind;
import com.smarthealthfinance.insights.domain.enums.InsightDetector;
import com.smarthealthfinance.insights.domain.enums.InsightSource;
import com.smarthealthfinance.insights.domain.enums.LimitationCode;
import com.smarthealthfinance.insights.domain.model.ChangeLine;
import com.smarthealthfinance.insights.domain.model.Contributor;
import com.smarthealthfinance.insights.domain.model.InsightAction;
import com.smarthealthfinance.insights.domain.model.InsightCandidate;
import com.smarthealthfinance.insights.domain.model.InsightContent;
import com.smarthealthfinance.insights.domain.model.InsightEvidence;
import com.smarthealthfinance.insights.domain.model.InsightInput;
import com.smarthealthfinance.insights.domain.model.InsightRefs;
import com.smarthealthfinance.insights.domain.model.KeyFigure;
import com.smarthealthfinance.insights.domain.model.Limitation;
import com.smarthealthfinance.insights.domain.model.Recommendation;
import com.smarthealthfinance.insights.domain.model.SeriesPoint;
import com.smarthealthfinance.insights.domain.model.SourceRef;
import com.smarthealthfinance.shared.domain.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Detectores de Cartões da {@code insights-v2} (ADR-0028): D11 fatura acima do padrão, D12 utilização alta e D14
 * fatura vencendo ou vencida. Os números são os do razão de Cards (ADR-0025), lidos pela porta {@code CardPortfolio};
 * nenhuma regra de fatura própria. Textos com o apelido do cartão, comerciante e categoria; nunca a descrição.
 */
final class CardDetectors {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal HALF = new BigDecimal("0.5");

    private CardDetectors() {
    }

    // ---------------------------------------------------------------------------------------------------------------
    // D11 — fatura acima do padrão
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * D11: a fatura em foco ≥ R$ 100 e ≥ 15% acima da média das até 3 faturas anteriores (ao menos 2), com a diferença
     * decomposta em fatores que somam exatamente o total.
     */
    static List<InsightCandidate> invoicesAbovePattern(DetectionContext ctx) {
        List<InsightCandidate> candidates = new ArrayList<>();
        for (CardPosition card : ctx.input().cards()) {
            focus(card, ctx.today()).ifPresent(focus -> abovePattern(ctx, card, focus).ifPresent(candidates::add));
        }
        return candidates;
    }

    /**
     * A fatura em avaliação: a última fechada até o vencimento efetivo; depois, a aberta a partir do 7º dia do ciclo.
     */
    static Optional<InvoiceStatement> focus(CardPosition card, LocalDate today) {
        Optional<InvoiceStatement> lastClosed = closed(card).stream()
                .max(Comparator.comparing(s -> s.dates().closingMonth()));
        if (lastClosed.isPresent() && !today.isAfter(lastClosed.get().dates().effectiveDueDate())) {
            return lastClosed;
        }
        InvoiceStatement open = card.current();
        long day = ChronoUnit.DAYS.between(open.dates().periodStart(), today) + 1;
        return day >= InsightRules.INVOICE_OPEN_FROM_DAY ? Optional.of(open) : Optional.empty();
    }

    private static Optional<InsightCandidate> abovePattern(DetectionContext ctx, CardPosition card,
                                                           InvoiceStatement focus) {
        YearMonth focusMonth = focus.dates().closingMonth();
        List<InvoiceStatement> base = closed(card).stream()
                .filter(s -> s.dates().closingMonth().isBefore(focusMonth))
                .filter(s -> s.charges().isPositive() || s.credits().isPositive())
                .sorted(Comparator.comparing(s -> s.dates().closingMonth()))
                .toList();
        base = base.subList(Math.max(0, base.size() - InsightRules.INVOICE_BASE), base.size());
        int m = base.size();
        if (m < InsightRules.INVOICE_MIN_BASE) {
            return Optional.empty();
        }
        Money zero = ctx.zero();
        Money baseTotal = base.stream().map(InvoiceStatement::amount).reduce(zero, Money::plus);
        Money average = divide(baseTotal, m);
        if (!average.isPositive()) {
            return Optional.empty();
        }
        Money delta = focus.amount().minus(average);
        if (delta.amount().compareTo(InsightRules.INVOICE_MIN_DELTA) < 0
                || delta.amount().multiply(HUNDRED)
                        .compareTo(InsightRules.INVOICE_MIN_PERCENT.multiply(average.amount())) < 0) {
            return Optional.empty();
        }

        boolean open = focus.status() == InvoiceStatus.OPEN;
        LocalDate today = ctx.today();
        String nickname = card.nickname();
        YearMonth dueMonth = focus.dates().dueMonth();
        String percent = InsightTexts.percent(delta.amount(), average.amount());
        List<YearMonth> baseMonths = base.stream().map(s -> s.dates().dueMonth()).toList();
        List<Contributor> factors = factors(ctx, card, focus, base, delta);

        String title = open
                ? "Sua fatura do %s está %s%% acima do seu padrão até agora, cerca de %s a mais.".formatted(nickname,
                        percent, InsightTexts.approx(delta))
                : "A fatura de %s do %s fechou %s%% acima do seu padrão, cerca de %s a mais.".formatted(
                        InsightTexts.monthName(dueMonth), nickname, percent, InsightTexts.approx(delta));
        String support = open
                ? "%s até %s, contra média de %s nas últimas %d faturas.".formatted(InsightTexts.money(focus.amount()),
                        InsightTexts.dayMonth(today), InsightTexts.money(average), m)
                : "%s, contra média de %s nas %d faturas anteriores (%s).".formatted(
                        InsightTexts.money(focus.amount()), InsightTexts.money(average), m,
                        InsightTexts.range(baseMonths));
        ChangeLine changeLine = new ChangeLine("Fatura do " + nickname,
                open ? "%s até %s".formatted(InsightTexts.money(focus.amount()), InsightTexts.dayMonth(today))
                        : "%s em %s".formatted(InsightTexts.money(focus.amount()), InsightTexts.monthName(dueMonth)),
                "+%s%% vs média das últimas %d".formatted(percent, m));
        Contributor top = factors.getFirst();
        Recommendation recommendation = new Recommendation("Revisar " + InsightTexts.lowerFirst(top.name()),
                "+%s em relação à média das %d faturas anteriores.".formatted(
                        InsightTexts.money(new Money(top.amount(), zero.currency())), m));

        LocalDate focusEnd = open ? today : focus.dates().closingDate().minusDays(1);
        LocalDate baseFrom = base.getFirst().dates().periodStart();
        LocalDate baseTo = base.getLast().dates().closingDate().minusDays(1);
        List<KeyFigure> figures = List.of(
                new KeyFigure("current", open ? "Fatura até agora" : "Fatura de " + InsightTexts.monthName(dueMonth),
                        FigureKind.MONEY, focus.amount().amount(), focus.dates().periodStart(), focusEnd),
                new KeyFigure("average", "Média das %d faturas anteriores".formatted(m), FigureKind.MONEY,
                        average.amount(), baseFrom, baseTo),
                new KeyFigure("difference", "Diferença", FigureKind.MONEY, delta.amount(), baseFrom, focusEnd));

        List<Limitation> limitations = new ArrayList<>();
        if (open) {
            limitations.add(new Limitation(LimitationCode.PARTIAL_INVOICE,
                    "A fatura ainda está aberta; o valor pode mudar até o fechamento em %s.".formatted(
                            InsightTexts.dayMonth(focus.dates().closingDate()))));
        }
        if (m < InsightRules.INVOICE_BASE) {
            limitations.add(new Limitation(LimitationCode.SHORT_HISTORY,
                    "A média usa só %d faturas.".formatted(m)));
        }
        String comparison = ("Comparamos a fatura de %s do %s com a média das %d faturas anteriores (%s). Cada "
                + "parcelamento entra como um fator próprio; compras à vista são agrupadas por categoria.").formatted(
                InsightTexts.monthName(dueMonth), nickname, m, InsightTexts.range(baseMonths));

        InsightEvidence evidence = new InsightEvidence(figures, invoiceSeries(card), factors, comparison, limitations,
                List.of(new SourceRef(InsightSource.CARDS_INVOICES, baseFrom, focusEnd)));
        InsightContent content = new InsightContent(title, support, nickname, changeLine, recommendation, evidence,
                List.of(viewInvoice(card, dueMonth, true), viewCard(card, false)), creditRefs());
        return Optional.of(new InsightCandidate(InsightDetector.INVOICE_ABOVE_PATTERN, dueMonth.toString(),
                card.cardId().value().toString(), delta, null, true, invoiceConfidence(focus, m, today), open,
                content));
    }

    /**
     * Fatores da diferença (07-design-decisions §13): compras à vista por categoria, cada parcelamento, estornos.
     * Diferença do grupo = valor na fatura em foco − média do grupo na base (zeros contam, 2 casas). Os 3 maiores
     * positivos aparecem; "Outros" fecha a conta (inclusive centavos de arredondamento): Σ fatores = Δ.
     */
    static List<Contributor> factors(DetectionContext ctx, CardPosition card, InvoiceStatement focus,
                                     List<InvoiceStatement> base, Money delta) {
        Map<String, Group> groups = new LinkedHashMap<>();
        collect(ctx, groups, card.lines().getOrDefault(focus.dates().closingMonth(), List.of()), true);
        for (InvoiceStatement statement : base) {
            collect(ctx, groups, card.lines().getOrDefault(statement.dates().closingMonth(), List.of()), false);
        }
        Money zero = ctx.zero();
        List<Contributor> all = new ArrayList<>();
        for (Map.Entry<String, Group> entry : groups.entrySet()) {
            Group group = entry.getValue();
            Money difference = group.focus.minus(divide(group.base, base.size()));
            all.add(new Contributor(ContributorKind.FACTOR, entry.getKey(), group.name, difference.amount(), null,
                    null, null));
        }
        List<Contributor> top = all.stream()
                .filter(c -> c.amount().signum() > 0)
                .sorted(Comparator.comparing(Contributor::amount).reversed().thenComparing(Contributor::key))
                .limit(InsightRules.INVOICE_FACTORS)
                .toList();
        List<Contributor> factors = new ArrayList<>(top);
        Money shown = top.stream().map(c -> new Money(c.amount(), zero.currency())).reduce(zero, Money::plus);
        Money others = delta.minus(shown);
        if (others.amount().signum() != 0 || factors.isEmpty()) {
            factors.add(new Contributor(ContributorKind.FACTOR, "others", "Outros", others.amount(), null, null,
                    null));
        }
        return factors;
    }

    private static void collect(DetectionContext ctx, Map<String, Group> groups, List<InvoiceLine> lines,
                                boolean focus) {
        for (InvoiceLine line : lines) {
            String key;
            String name;
            Money value;
            if (line.kind() == InvoiceItemKind.CREDIT) {
                key = "credits";
                name = "Estornos";
                value = line.net().negate();
            }
            else if (line.installmentCount() != null && line.installmentCount() > 1) {
                key = "plan:" + line.transactionId();
                name = installmentName(ctx, line);
                value = line.net();
            }
            else {
                key = "category:" + (line.categoryCode() == null ? "none" : line.categoryCode());
                name = ctx.categoryName(line.categoryCode());
                value = line.net();
            }
            Group group = groups.computeIfAbsent(key, ignored -> new Group(name, ctx.zero()));
            if (focus) {
                // O nome do parcelamento leva o número da parcela da fatura em foco.
                group.name = name;
                group.focus = group.focus.plus(value);
            }
            else {
                group.base = group.base.plus(value);
            }
        }
    }

    private static String installmentName(DetectionContext ctx, InvoiceLine line) {
        String subject = line.merchantName() != null ? "Parcela de " + line.merchantName()
                : line.categoryCode() != null ? "Parcela em " + InsightTexts.lowerFirst(ctx.categoryName(
                        line.categoryCode()))
                : "Parcelamento";
        return "%s (%d/%d)".formatted(subject, line.installmentNumber(), line.installmentCount());
    }

    private static final class Group {
        private String name;
        private Money focus;
        private Money base;

        private Group(String name, Money zero) {
            this.name = name;
            this.focus = zero;
            this.base = zero;
        }
    }

    private static Confidence invoiceConfidence(InvoiceStatement focus, int m, LocalDate today) {
        if (focus.status() != InvoiceStatus.OPEN) {
            return m >= InsightRules.INVOICE_BASE ? Confidence.HIGH : Confidence.MEDIUM;
        }
        long day = ChronoUnit.DAYS.between(focus.dates().periodStart(), today) + 1;
        return day >= InsightRules.INVOICE_MEDIUM_FROM_DAY ? Confidence.MEDIUM : Confidence.LOW;
    }

    /** As até 6 últimas faturas iniciadas, pelo mês de vencimento; a aberta marcada como parcial. */
    private static List<SeriesPoint> invoiceSeries(CardPosition card) {
        List<InvoiceStatement> started = card.statements().stream()
                .filter(s -> s.status() != InvoiceStatus.FUTURE)
                .sorted(Comparator.comparing(s -> s.dates().closingMonth()))
                .toList();
        return started.subList(Math.max(0, started.size() - 6), started.size()).stream()
                .map(s -> new SeriesPoint(s.dates().dueMonth(), s.amount().amount(),
                        s.status() == InvoiceStatus.OPEN))
                .toList();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // D12 — utilização alta
    // ---------------------------------------------------------------------------------------------------------------

    /** D12: cartão com utilização ≥ 50% do limite (com parcelas futuras); Importante só com ≥ 80%. */
    static List<InsightCandidate> highUtilization(DetectionContext ctx) {
        List<InsightCandidate> candidates = new ArrayList<>();
        LocalDate today = ctx.today();
        YearMonth previousMonth = YearMonth.from(today).minusMonths(1);
        for (CardPosition card : ctx.input().cards()) {
            Money utilized = card.utilization().utilized();
            Money limit = card.limit();
            BigDecimal scaled = utilized.amount().multiply(HUNDRED);
            if (scaled.compareTo(InsightRules.UTILIZATION_MIN_PERCENT.multiply(limit.amount())) < 0) {
                continue;
            }
            boolean important = scaled.compareTo(InsightRules.UTILIZATION_IMPORTANT_PERCENT.multiply(limit.amount()))
                    >= 0;
            String band = !important ? "50-79" : scaled.compareTo(HUNDRED.multiply(limit.amount())) < 0 ? "80-99"
                    : "100+";
            Money impact = utilized.minus(new Money(limit.amount().multiply(HALF), limit.currency()));
            int percent = card.utilization().utilizationPercent();
            Optional<CardPosition> previous = atMonthEnd(ctx.input(), previousMonth, card);
            boolean future = card.futureInstallments().isPositive();
            String nickname = card.nickname();

            String title = future
                    ? "A utilização do %s está em %d%% do limite, incluindo parcelas futuras.".formatted(nickname,
                            percent)
                    : "A utilização do %s está em %d%% do limite.".formatted(nickname, percent);
            List<String> parts = new ArrayList<>();
            previous.ifPresent(p -> parts.add("Era %d%% no fim de %s.".formatted(p.utilization().utilizationPercent(),
                    InsightTexts.monthName(previousMonth))));
            if (future) {
                parts.add("Sem as parcelas futuras, seria %d%%.".formatted(
                        card.utilization().utilizationWithoutFuturePercent()));
            }
            if (parts.isEmpty()) {
                parts.add("%s de %s em uso.".formatted(InsightTexts.money(utilized), InsightTexts.money(limit)));
            }
            ChangeLine changeLine = new ChangeLine("Utilização do " + nickname, "%d%% do limite".formatted(percent),
                    previous.map(p -> "era %d%% em %s".formatted(p.utilization().utilizationPercent(),
                                    InsightTexts.monthName(previousMonth)))
                            .orElse(future ? "incluindo parcelas futuras"
                                    : "%s em uso".formatted(InsightTexts.money(utilized))));

            List<KeyFigure> figures = List.of(
                    figure("utilized", "Em uso, com parcelas futuras", utilized, today),
                    figure("limit", "Limite", limit, today),
                    new KeyFigure("utilization", "Utilização", FigureKind.PERCENT, BigDecimal.valueOf(percent), today,
                            today),
                    figure("currentInvoice", "Fatura atual", card.utilization().currentInvoice(), today),
                    figure("previousOutstanding", "Faturas anteriores em aberto",
                            card.utilization().previousOutstanding(), today),
                    figure("futureInstallments", "Parcelas futuras", card.futureInstallments(), today));
            List<SeriesPoint> series = utilizationSeries(ctx.input(), card, today);
            String comparison = ("Dividimos o que está em uso no %s (faturas em aberto, fatura atual e parcelas "
                    + "futuras) pelo limite atual do cartão. Nos meses anteriores, usamos a posição do último dia de "
                    + "cada mês.").formatted(nickname);
            InsightEvidence evidence = new InsightEvidence(figures, series, List.of(), comparison,
                    List.of(cardPayments()), List.of(new SourceRef(InsightSource.CARDS_UTILIZATION,
                            series.getFirst().month().atDay(1), today)));
            InsightContent content = new InsightContent(title, String.join(" ", parts), nickname, changeLine, null,
                    evidence, List.of(viewCard(card, true),
                            InsightAction.secondary(ActionKind.VIEW_HEALTH, "Ver saúde", Map.of())),
                    creditRefs());
            candidates.add(new InsightCandidate(InsightDetector.CARD_UTILIZATION_HIGH, null,
                    card.cardId().value() + "|" + band, impact, null, important, Confidence.HIGH, false, content));
        }
        return candidates;
    }

    private static Optional<CardPosition> atMonthEnd(InsightInput input, YearMonth month, CardPosition card) {
        return input.cardsAtMonthEnd().getOrDefault(month, List.of()).stream()
                .filter(p -> p.cardId().equals(card.cardId()))
                .findFirst();
    }

    /** Utilização no último dia de cada um dos 6 meses anteriores (quando o cartão já era acompanhado) e hoje. */
    private static List<SeriesPoint> utilizationSeries(InsightInput input, CardPosition card, LocalDate today) {
        List<SeriesPoint> series = new ArrayList<>();
        YearMonth current = YearMonth.from(today);
        for (int back = 6; back >= 1; back--) {
            YearMonth month = current.minusMonths(back);
            atMonthEnd(input, month, card).ifPresent(p -> series.add(new SeriesPoint(month,
                    BigDecimal.valueOf(p.utilization().utilizationPercent()), false)));
        }
        series.add(new SeriesPoint(current, BigDecimal.valueOf(card.utilization().utilizationPercent()), true));
        return series;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // D14 — fatura vencendo ou vencida
    // ---------------------------------------------------------------------------------------------------------------

    /** D14: fatura fechada com saldo que vence em até 5 dias (Informativo) ou já venceu (pode ser Importante). */
    static List<InsightCandidate> invoicesDue(DetectionContext ctx) {
        List<InsightCandidate> candidates = new ArrayList<>();
        LocalDate today = ctx.today();
        for (CardPosition card : ctx.input().cards()) {
            for (InvoiceStatement invoice : closed(card)) {
                if (!invoice.remaining().isPositive()) {
                    continue;
                }
                LocalDate due = invoice.dates().dueDate();
                LocalDate effective = invoice.dates().effectiveDueDate();
                boolean overdue = today.isAfter(effective);
                if (!overdue && ChronoUnit.DAYS.between(today, due) > InsightRules.INVOICE_DUE_SOON_DAYS) {
                    continue;
                }
                candidates.add(invoiceDue(ctx, card, invoice, overdue,
                        overdue || !today.isAfter(due) ? due : effective));
            }
        }
        return candidates;
    }

    private static InsightCandidate invoiceDue(DetectionContext ctx, CardPosition card, InvoiceStatement invoice,
                                               boolean overdue, LocalDate shownDue) {
        LocalDate today = ctx.today();
        YearMonth dueMonth = invoice.dates().dueMonth();
        String nickname = card.nickname();
        String month = InsightTexts.monthName(dueMonth);
        String title = overdue
                ? "A fatura de %s do %s venceu em %s com %s em aberto.".formatted(month, nickname,
                        InsightTexts.dayMonth(shownDue), InsightTexts.money(invoice.remaining()))
                : "A fatura de %s do %s vence em %s: %s em aberto.".formatted(month, nickname,
                        InsightTexts.dayMonth(shownDue), InsightTexts.money(invoice.remaining()));
        String support = "Se você já pagou, registre o pagamento ou marque o débito do extrato como pagamento de "
                + "fatura.";
        LocalDate from = invoice.dates().periodStart();
        List<KeyFigure> figures = List.of(
                figure("amount", "Valor da fatura", invoice.amount(), from, invoice.dates().closingDate().minusDays(1)),
                figure("paid", "Pago", invoice.paid(), from, today),
                figure("remaining", "Em aberto", invoice.remaining(), from, today));
        String comparison = ("Usamos o valor da fatura de %s do %s e os pagamentos registrados para o cartão até hoje. "
                + "Os pagamentos quitam primeiro a fatura mais antiga.").formatted(month, nickname);
        InsightEvidence evidence = new InsightEvidence(figures, List.of(), List.of(), comparison,
                List.of(cardPayments()), List.of(new SourceRef(InsightSource.CARDS_INVOICES, from, today)));
        InsightContent content = new InsightContent(title, support, nickname, null, null, evidence,
                List.of(InsightAction.primary(ActionKind.RECORD_CARD_PAYMENT, "Registrar pagamento",
                                Map.of("cardId", card.cardId().value().toString())),
                        viewInvoice(card, dueMonth, false)),
                creditRefs());
        return new InsightCandidate(InsightDetector.INVOICE_DUE, dueMonth.toString(),
                card.cardId().value() + "|" + (overdue ? "OVERDUE" : "DUE_SOON"), invoice.remaining(), null, overdue,
                Confidence.HIGH, false, content);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // ciclo de vida
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Faturas ainda em avaliação ({@code cardId|YYYY-MM} do vencimento): a fatura em foco do D11 e as fechadas com
     * saldo do D14. Um insight de fatura não detectado resolve se a fatura está aqui; senão expira (ADR-0028 §5).
     */
    static Set<String> evaluatedInvoices(InsightInput input, LocalDate today) {
        Set<String> keys = new LinkedHashSet<>();
        for (CardPosition card : input.cards()) {
            String cardId = card.cardId().value().toString();
            focus(card, today).ifPresent(focus -> keys.add(cardId + "|" + focus.dates().dueMonth()));
            closed(card).stream().filter(s -> s.remaining().isPositive())
                    .forEach(s -> keys.add(cardId + "|" + s.dates().dueMonth()));
        }
        return keys;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // apoio
    // ---------------------------------------------------------------------------------------------------------------

    /** Faturas já fechadas do razão (fora as futuras e a aberta). */
    private static List<InvoiceStatement> closed(CardPosition card) {
        return card.statements().stream()
                .filter(s -> s.status() != InvoiceStatus.FUTURE && s.status() != InvoiceStatus.OPEN)
                .toList();
    }

    private static Money divide(Money total, int count) {
        return new Money(total.amount().divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_EVEN),
                total.currency());
    }

    private static KeyFigure figure(String code, String label, Money value, LocalDate day) {
        return figure(code, label, value, day, day);
    }

    private static KeyFigure figure(String code, String label, Money value, LocalDate from, LocalDate to) {
        return new KeyFigure(code, label, FigureKind.MONEY, value.amount(), from, to);
    }

    private static Limitation cardPayments() {
        return new Limitation(LimitationCode.CARD_PAYMENTS,
                "Considera os pagamentos registrados ou importados até hoje.");
    }

    private static InsightAction viewInvoice(CardPosition card, YearMonth dueMonth, boolean primary) {
        Map<String, String> params = Map.of("cardId", card.cardId().value().toString(), "dueMonth",
                dueMonth.toString());
        return primary ? InsightAction.primary(ActionKind.VIEW_INVOICE, "Ver fatura", params)
                : InsightAction.secondary(ActionKind.VIEW_INVOICE, "Ver fatura", params);
    }

    private static InsightAction viewCard(CardPosition card, boolean primary) {
        Map<String, String> params = Map.of("cardId", card.cardId().value().toString());
        return primary ? InsightAction.primary(ActionKind.VIEW_CARD, "Ver cartão", params)
                : InsightAction.secondary(ActionKind.VIEW_CARD, "Ver cartão", params);
    }

    private static InsightRefs creditRefs() {
        return new InsightRefs(null, null, null, null, HealthDimension.CREDIT);
    }
}
