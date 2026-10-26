package com.smarthealthfinance.health.domain.model;

import com.smarthealthfinance.cards.domain.valueobject.CardPosition;
import com.smarthealthfinance.analytics.domain.service.FlowLedger;
import com.smarthealthfinance.analytics.domain.valueobject.AnalysisPeriod;
import com.smarthealthfinance.shared.domain.Money;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Tudo o que o cálculo de um mês precisa, já lido das portas (ADR-0018 §4). Nenhum I/O depois daqui.
 *
 * @param period         mês de referência; parcial até a data-base no mês corrente
 * @param firstActivity  primeiro dia com movimento no fluxo de caixa; vazio = nenhum
 * @param ledger         linhas do fluxo de caixa cobrindo a janela, as bases de comparação e a data-base
 * @param reserves       contas {@code SAVINGS} ativas com o saldo na data-base
 * @param activeAccounts contas ativas do Workspace (só contexto)
 * @param categoryNames  nome de cada categoria do catálogo, para os textos
 * @param cards          cartões ativos acompanhados, com a posição na data-base (ADR-0027 §1)
 */
public record HealthInput(AnalysisPeriod period, Optional<LocalDate> firstActivity, FlowLedger ledger,
                          List<ReserveBalance> reserves, int activeAccounts, Map<String, String> categoryNames,
                          List<CardPosition> cards) {

    public HealthInput(AnalysisPeriod period, Optional<LocalDate> firstActivity, FlowLedger ledger,
                       List<ReserveBalance> reserves, int activeAccounts, Map<String, String> categoryNames) {
        this(period, firstActivity, ledger, reserves, activeAccounts, categoryNames, List.of());
    }

    public record ReserveBalance(UUID accountId, Money balance) {

        public ReserveBalance {
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(balance, "balance");
        }
    }

    public HealthInput {
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(firstActivity, "firstActivity");
        Objects.requireNonNull(ledger, "ledger");
        reserves = List.copyOf(reserves);
        categoryNames = Map.copyOf(categoryNames);
        cards = List.copyOf(cards);
    }

    public YearMonth month() {
        return period.month();
    }

    /** Data em que o resultado é medido: hoje no mês corrente, o último dia nos meses fechados. */
    public LocalDate asOf() {
        return period.to();
    }
}
