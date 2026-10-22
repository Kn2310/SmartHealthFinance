package com.smarthealthfinance.cards.presentation.dto.response;

import com.smarthealthfinance.cards.application.dto.CardView;
import com.smarthealthfinance.cards.application.dto.CardsSummaryView;
import com.smarthealthfinance.cards.application.dto.FutureInvoicesView;
import com.smarthealthfinance.cards.application.dto.InvoiceItemPageView;
import com.smarthealthfinance.cards.application.dto.InvoiceSummaryView;
import com.smarthealthfinance.cards.application.dto.InvoiceView;
import com.smarthealthfinance.cards.application.dto.UtilizationView;
import com.smarthealthfinance.cards.application.dto.InvoiceItemView;
import com.smarthealthfinance.cards.application.dto.InstallmentPlanView;
import com.smarthealthfinance.cards.application.dto.CardPurchaseView;
import com.smarthealthfinance.transactions.presentation.dto.response.TransactionResponse;
import com.smarthealthfinance.shared.domain.Money;
import com.smarthealthfinance.shared.presentation.money.MoneyDto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Respostas de Cartões e Faturas (ADR-0026): dinheiro em {@link MoneyDto} na escala de exibição, meses como
 * {@code YYYY-MM}, enums como string. Todos os valores já vêm calculados pelo backend; o frontend só exibe.
 */
public final class CardResponses {

    private CardResponses() {
    }

    public record CardResponse(UUID id, UUID workspaceId, String nickname, String institutionName, String brand,
                               String last4, MoneyDto limit, int closingDay, int dueDay,
                               UUID defaultPaymentAccountId, String status, Instant createdAt, Instant updatedAt) {

        public static CardResponse from(CardView view) {
            return new CardResponse(view.id(), view.workspaceId(), view.nickname(), view.institutionName(),
                    view.brand().name(), view.last4(), money(view.limit()), view.closingDay(), view.dueDay(),
                    view.defaultPaymentAccountId(), view.status().name(), view.createdAt(), view.updatedAt());
        }
    }

    public record CardListResponse(List<CardResponse> items) {

        public static CardListResponse from(List<CardView> views) {
            return new CardListResponse(views.stream().map(CardResponse::from).toList());
        }
    }

    /**
     * @param partial        {@code true} enquanto a fatura está aberta: o valor pode mudar até o fechamento
     * @param minimumPayment nulo até a importação informar
     */
    public record InvoiceSummaryResponse(String dueMonth, LocalDate periodStart, LocalDate closingDate,
                                         LocalDate dueDate, LocalDate effectiveDueDate, String datesSource,
                                         String status, MoneyDto charges, MoneyDto credits, MoneyDto amount,
                                         MoneyDto paid, MoneyDto remaining, MoneyDto minimumPayment,
                                         boolean partial) {

        public static InvoiceSummaryResponse from(InvoiceSummaryView view) {
            return new InvoiceSummaryResponse(view.dueMonth().toString(), view.periodStart(), view.closingDate(),
                    view.dueDate(), view.effectiveDueDate(), view.datesSource().name(), view.status().name(),
                    money(view.charges()), money(view.credits()), money(view.amount()), money(view.paid()),
                    money(view.remaining()), money(view.minimumPayment()), view.partial());
        }
    }

    public record InvoiceListResponse(List<InvoiceSummaryResponse> items) {

        public static InvoiceListResponse from(List<InvoiceSummaryView> views) {
            return new InvoiceListResponse(views.stream().map(InvoiceSummaryResponse::from).toList());
        }
    }

    public record InvoiceResponse(InvoiceSummaryResponse invoice, long itemCount, LocalDate asOf,
                                  String calculationVersion) {

        public static InvoiceResponse from(InvoiceView view) {
            return new InvoiceResponse(InvoiceSummaryResponse.from(view.invoice()), view.itemCount(), view.asOf(),
                    view.calculationVersion());
        }
    }

    /** @param installment nulo em créditos */
    public record InvoiceItemResponse(String kind, UUID transactionId, LocalDate occurredOn, String description,
                                      String categoryCode, String merchantName, String source,
                                      InstallmentRef installment, MoneyDto amount, MoneyDto cancelledAmount) {

        public record InstallmentRef(int number, int count) {}

        static InvoiceItemResponse from(InvoiceItemView item) {
            return new InvoiceItemResponse(item.kind().name(), item.transactionId(), item.occurredOn(),
                    item.description(), item.categoryCode(), item.merchantName(), item.source(),
                    item.installmentNumber() == null ? null
                            : new InstallmentRef(item.installmentNumber(), item.installmentCount()),
                    money(item.amount()), money(item.cancelledAmount()));
        }
    }

    /** Paginação por offset (spec 05.6), no envelope de {@code /transactions}. */
    public record InvoiceItemPageResponse(List<InvoiceItemResponse> items, int page, int pageSize,
                                          long totalItems) {

        public static InvoiceItemPageResponse from(InvoiceItemPageView view) {
            return new InvoiceItemPageResponse(view.items().stream().map(InvoiceItemResponse::from).toList(),
                    view.page(), view.pageSize(), view.totalItems());
        }
    }

    public record FutureInstallmentResponse(UUID transactionId, String description, int number, int count,
                                            MoneyDto amount) {

        static FutureInstallmentResponse from(FutureInvoicesView.Installment installment) {
            return new FutureInstallmentResponse(installment.transactionId(), installment.description(),
                    installment.number(), installment.count(), money(installment.amount()));
        }
    }

    public record FutureInvoiceMonthResponse(String dueMonth, MoneyDto committed,
                                             List<FutureInstallmentResponse> installments) {}

    /** Só o valor já comprometido em parcelas; nenhuma estimativa (ADR-0025 §10). */
    public record FutureInvoicesResponse(List<FutureInvoiceMonthResponse> months, MoneyDto totalCommitted,
                                         LocalDate asOf, String calculationVersion) {

        public static FutureInvoicesResponse from(FutureInvoicesView view) {
            return new FutureInvoicesResponse(view.months().stream()
                    .map(month -> new FutureInvoiceMonthResponse(month.dueMonth().toString(),
                            money(month.committed()),
                            month.installments().stream().map(FutureInstallmentResponse::from).toList()))
                    .toList(), money(view.totalCommitted()), view.asOf(), view.calculationVersion());
        }
    }

    public record UtilizationCompositionResponse(MoneyDto previousOutstanding, MoneyDto currentInvoice,
                                                 MoneyDto futureInstallments) {}

    /**
     * @param available          limite − utilizado; negativo quando o limite foi excedido
     * @param utilizationPercent inteiro, HALF_EVEN
     */
    public record UtilizationResponse(MoneyDto limit, MoneyDto utilized, MoneyDto available, MoneyDto credit,
                                      int utilizationPercent, int utilizationWithoutFuturePercent,
                                      UtilizationCompositionResponse composition, LocalDate asOf,
                                      String calculationVersion) {

        public static UtilizationResponse from(UtilizationView view) {
            return new UtilizationResponse(money(view.limit()), money(view.utilized()), money(view.available()),
                    money(view.credit()), view.utilizationPercent(), view.utilizationWithoutFuturePercent(),
                    new UtilizationCompositionResponse(money(view.composition().previousOutstanding()),
                            money(view.composition().currentInvoice()),
                            money(view.composition().futureInstallments())),
                    view.asOf(), view.calculationVersion());
        }
    }

    public record CardSummaryResponse(CardResponse card, InvoiceSummaryResponse currentInvoice,
                                      UtilizationResponse utilization, MoneyDto nextInvoiceCommitted) {}

    /**
     * @param openInvoices soma do que falta pagar nas faturas que já começaram
     * @param nextDueDate  vencimento mais próximo com valor em aberto; nulo se não houver
     */
    public record CardsSummaryResponse(MoneyDto totalLimit, MoneyDto utilized, MoneyDto openInvoices,
                                       LocalDate nextDueDate, List<CardSummaryResponse> cards, LocalDate asOf,
                                       String calculationVersion) {

        public static CardsSummaryResponse from(CardsSummaryView view) {
            return new CardsSummaryResponse(money(view.totalLimit()), money(view.utilized()),
                    money(view.openInvoices()), view.nextDueDate(), view.cards().stream()
                    .map(card -> new CardSummaryResponse(CardResponse.from(card.card()),
                            InvoiceSummaryResponse.from(card.currentInvoice()),
                            UtilizationResponse.from(card.utilization()), money(card.nextInvoiceCommitted())))
                    .toList(), view.asOf(), view.calculationVersion());
        }
    }

    public record PurchaseInstallmentResponse(int number, MoneyDto amount, String dueMonth) {}

    /** Compra no cartão e as parcelas geradas (ADR-0026 §10). */
    public record CardPurchaseResponse(TransactionResponse transaction, List<PurchaseInstallmentResponse> installments,
                                       String calculationVersion) {

        public static CardPurchaseResponse from(CardPurchaseView view) {
            return new CardPurchaseResponse(TransactionResponse.from(view.transaction()), view.installments().stream()
                    .map(part -> new PurchaseInstallmentResponse(part.number(), money(part.amount()),
                            part.dueMonth().toString()))
                    .toList(), view.calculationVersion());
        }
    }

    /** Pagamento de fatura (TRANSFER conta → cartão) ou estorno (REFUND no cartão). */
    public record CardTransactionResponse(TransactionResponse transaction) {}

    /** @param amount parcela contratada; o que fica na fatura é {@code amount − cancelledAmount} */
    public record InstallmentItemResponse(int number, String dueMonth, MoneyDto amount, MoneyDto cancelledAmount,
                                          String status) {}

    public record ReservedLimitResponse(String dueMonth, MoneyDto amount) {}

    /**
     * @param currentNumber         parcela na fatura atual (ou fechada não paga); nula se não houver
     * @param remainingAfterCurrent soma das parcelas em faturas futuras
     */
    public record InstallmentPlanResponse(UUID transactionId, String description, LocalDate purchasedOn,
                                          MoneyDto total, int count, MoneyDto installmentAmount, int paidCount,
                                          MoneyDto paidAmount, Integer currentNumber, MoneyDto remainingAfterCurrent,
                                          String lastDueMonth, String status,
                                          List<InstallmentItemResponse> installments,
                                          List<ReservedLimitResponse> reservedLimit) {

        public static InstallmentPlanResponse from(InstallmentPlanView view) {
            return new InstallmentPlanResponse(view.transactionId(), view.description(), view.purchasedOn(),
                    money(view.total()), view.count(), money(view.installmentAmount()), view.paidCount(),
                    money(view.paidAmount()), view.currentNumber(), money(view.remainingAfterCurrent()),
                    view.lastDueMonth().toString(), view.status().name(), view.installments().stream()
                    .map(item -> new InstallmentItemResponse(item.number(), item.dueMonth().toString(),
                            money(item.amount()), money(item.cancelledAmount()), item.status().name()))
                    .toList(), view.reservedLimit().stream()
                    .map(reserved -> new ReservedLimitResponse(reserved.dueMonth().toString(),
                            money(reserved.amount())))
                    .toList());
        }
    }

    public record InstallmentPlanListResponse(List<InstallmentPlanResponse> items) {

        public static InstallmentPlanListResponse from(List<InstallmentPlanView> views) {
            return new InstallmentPlanListResponse(views.stream().map(InstallmentPlanResponse::from).toList());
        }
    }

    static MoneyDto money(Money value) {
        return value == null ? null : new MoneyDto(value.toPlainString(), value.currency().getCurrencyCode());
    }
}
