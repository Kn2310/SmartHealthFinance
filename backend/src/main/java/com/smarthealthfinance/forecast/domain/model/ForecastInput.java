package com.smarthealthfinance.forecast.domain.model;

import com.smarthealthfinance.cards.domain.valueobject.CardPosition;
import com.smarthealthfinance.forecast.domain.enums.UpcomingEventKind;
import com.smarthealthfinance.shared.domain.Money;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Tudo o que o cálculo precisa, já lido das portas (ADR-0031 §1). Nenhum I/O depois daqui; "hoje" entra como
 * parâmetro (data de negócio, ADR-0006 §10).
 *
 * @param entries        transações POSTED do fluxo de caixa (contas e cartões ativos), do histórico até hoje
 * @param existingSeries séries persistidas, com o estado do usuário (podem estar vazias)
 * @param totalBalance   saldo total de hoje das contas que entram no total do Overview (fato)
 * @param cards          cartões ativos com a posição de hoje (ADR-0027 §1)
 * @param invoiceEvents  fatos de fatura do ADR-0006 §18 (parcial, aberta, atrasada), já calculados pelo Overview
 */
public record ForecastInput(LocalDate today, Currency currency, Optional<LocalDate> firstActivity,
                            List<ForecastEntry> entries, List<RecurringSeries> existingSeries, Money totalBalance,
                            List<CardPosition> cards, List<InvoiceEventFact> invoiceEvents) {

    public ForecastInput {
        Objects.requireNonNull(today, "today");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(firstActivity, "firstActivity");
        Objects.requireNonNull(totalBalance, "totalBalance");
        entries = List.copyOf(entries);
        existingSeries = List.copyOf(existingSeries);
        cards = List.copyOf(cards);
        invoiceEvents = List.copyOf(invoiceEvents);
    }

    public YearMonth month() {
        return YearMonth.from(today);
    }

    public Money zero() {
        return Money.zero(currency);
    }

    /**
     * Evento de fatura que já é fato (ADR-0030 §3.3): {@code INVOICE_PARTIAL}, {@code INVOICE_OPEN} ou
     * {@code INVOICE_OVERDUE}.
     */
    public record InvoiceEventFact(UUID cardId, String cardNickname, YearMonth closingMonth, UpcomingEventKind kind,
                                   LocalDate date, Money amount) {

        public InvoiceEventFact {
            Objects.requireNonNull(cardId, "cardId");
            Objects.requireNonNull(closingMonth, "closingMonth");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(date, "date");
            Objects.requireNonNull(amount, "amount");
            if (kind != UpcomingEventKind.INVOICE_PARTIAL && kind != UpcomingEventKind.INVOICE_OPEN
                    && kind != UpcomingEventKind.INVOICE_OVERDUE) {
                throw new IllegalArgumentException("Só fatos de fatura");
            }
        }
    }
}
