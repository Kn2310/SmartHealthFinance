package com.smarthealthfinance.cards.application.dto;

import com.smarthealthfinance.cards.domain.enums.InstallmentPlanStatus;
import com.smarthealthfinance.cards.domain.enums.InstallmentStatus;
import com.smarthealthfinance.shared.domain.Money;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * Parcelamento de uma compra (ADR-0025 §6, ADR-0026 §10), com os estornos já aplicados.
 *
 * @param paidAmount            soma das parcelas em faturas pagas
 * @param currentNumber         parcela na fatura atual ou numa fechada ainda não paga; nulo se não houver
 * @param remainingAfterCurrent soma das parcelas em faturas futuras (o "limite reservado" de hoje)
 * @param reservedLimit         para cada fatura do parcelamento, o que ainda fica reservado do limite nela
 * @param currentInvoiceShare   peso do parcelamento na fatura aberta (ADR-0029 §5); nulo sem parcela nela ou com a
 *                              fatura sem valor positivo
 */
public record InstallmentPlanView(UUID transactionId, String description, LocalDate purchasedOn, Money total,
                                  int count, Money installmentAmount, int paidCount, Money paidAmount,
                                  Integer currentNumber, Money remainingAfterCurrent, YearMonth lastDueMonth,
                                  InstallmentPlanStatus status, List<Item> installments, List<Reserved> reservedLimit,
                                  CurrentInvoiceShare currentInvoiceShare) {

    /** @param amount parcela contratada; o que fica na fatura é {@code amount − cancelledAmount} */
    public record Item(int number, YearMonth dueMonth, Money amount, Money cancelledAmount, InstallmentStatus status) {}

    public record Reserved(YearMonth dueMonth, Money amount) {}

    /**
     * @param installment o que este parcelamento põe na fatura aberta, com o estorno já aplicado
     * @param percent     installment ÷ invoiceAmount, inteiro HALF_EVEN
     */
    public record CurrentInvoiceShare(YearMonth dueMonth, Money installment, Money invoiceAmount, int percent) {}
}
