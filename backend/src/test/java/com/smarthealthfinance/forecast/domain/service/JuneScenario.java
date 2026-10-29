package com.smarthealthfinance.forecast.domain.service;

import com.smarthealthfinance.forecast.domain.model.ForecastInput;
import com.smarthealthfinance.transactions.domain.enums.CashFlowRole;

/**
 * Cenário pequeno, conferível à mão: hoje = 10/06/2026 (quarta), 3 meses fechados (mar–mai), saldo de R$ 10.000,00.
 * <ul>
 *   <li>salário "empresa" R$ 5.000,00 no dia 5 (mar–mai, e já recebido em 05/06);</li>
 *   <li>aluguel R$ 950,00 / 1.000,00 / 1.050,00 no dia 15 (mediana 1.000,00; faixa 950–1.050; amplitude 10%);</li>
 *   <li>mercado: duas compras de R$ 150,00 por mês (dias 8 e 20), logo nunca recorrente; 150,00 já gasto em 08/06.</li>
 * </ul>
 */
final class JuneScenario {

    private JuneScenario() {
    }

    static ForecastFixture fixture() {
        ForecastFixture fixture = new ForecastFixture()
                .monthly(CashFlowRole.INCOME, "empresa", 5, "SALARY", "2026-03=5000.00", "2026-04=5000.00",
                        "2026-05=5000.00", "2026-06=5000.00")
                .monthly(CashFlowRole.EXPENSE, "aluguel", 15, "HOUSING", "2026-03=950.00", "2026-04=1000.00",
                        "2026-05=1050.00");
        for (String month : new String[] {"2026-03", "2026-04", "2026-05", "2026-06"}) {
            fixture.add(month + "-08", CashFlowRole.EXPENSE, "GROCERIES", "mercado", "150.00");
        }
        for (String month : new String[] {"2026-03", "2026-04", "2026-05"}) {
            fixture.add(month + "-20", CashFlowRole.EXPENSE, "GROCERIES", "mercado", "150.00");
        }
        return fixture;
    }

    static ForecastInput input() {
        return fixture().input("2026-06-10", "2026-03-01", "10000.00");
    }
}
