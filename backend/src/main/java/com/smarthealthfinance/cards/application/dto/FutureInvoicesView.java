package com.smarthealthfinance.cards.application.dto;

import com.smarthealthfinance.cards.application.port.CardLedgerReader.CommittedInstallment;
import com.smarthealthfinance.shared.domain.Money;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/** Só o valor já comprometido em parcelas (ADR-0025 §10). Nenhuma estimativa. */
public record FutureInvoicesView(List<Month> months, Money totalCommitted, LocalDate asOf,
                                 String calculationVersion) {

    public record Month(YearMonth dueMonth, Money committed, List<CommittedInstallment> installments) {}
}
