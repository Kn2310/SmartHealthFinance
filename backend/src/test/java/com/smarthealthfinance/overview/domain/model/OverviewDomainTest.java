package com.smarthealthfinance.overview.domain.model;

import com.smarthealthfinance.accounts.domain.valueobject.AccountId;
import com.smarthealthfinance.identity.domain.model.Workspace;
import com.smarthealthfinance.overview.domain.enums.MovementFlow;
import com.smarthealthfinance.overview.domain.enums.OverviewState;
import com.smarthealthfinance.transactions.domain.enums.AdjustmentDirection;
import com.smarthealthfinance.transactions.domain.enums.TransactionType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.smarthealthfinance.identity.IdentityFixtures.CREATED_AT;
import static com.smarthealthfinance.transactions.TransactionsFixtures.brl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class OverviewDomainTest {

    private static final java.util.Currency BRL = Workspace.DEFAULT_BASE_CURRENCY;

    // --- CashFlow ---

    @Test
    void netIsIncomePlusRefundsMinusExpense() {
        CashFlow flow = new CashFlow(brl("6800"), brl("3846.20"), brl("100"));

        assertThat(flow.net()).isEqualTo(brl("3053.80"));
    }

    @Test
    void netCanBeNegative() {
        assertThat(new CashFlow(brl("100"), brl("250.50"), brl("0")).net()).isEqualTo(brl("-150.50"));
    }

    @Test
    void emptyFlowIsAllZero() {
        CashFlow flow = CashFlow.empty(BRL);

        assertThat(flow.income()).isEqualTo(brl("0"));
        assertThat(flow.expense()).isEqualTo(brl("0"));
        assertThat(flow.refunds()).isEqualTo(brl("0"));
        assertThat(flow.net()).isEqualTo(brl("0"));
    }

    @Test
    void netKeepsExactDecimalsWithoutFloatingPointDrift() {
        assertThat(new CashFlow(brl("0.10"), brl("0.30"), brl("0.20")).net()).isEqualTo(brl("0"));
    }

    // --- AccountPosition ---

    @Test
    void totalSumsOnlyAccountsIncludedInTotal() {
        List<AccountPosition> positions = List.of(
                position(true, "1000.00"), position(true, "-250.40"), position(false, "99999.00"));

        assertThat(AccountPosition.totalOf(positions, BRL)).isEqualTo(brl("749.60"));
    }

    @Test
    void totalOfNothingIsZero() {
        assertThat(AccountPosition.totalOf(List.of(), BRL)).isEqualTo(brl("0"));
    }

    @Test
    void negativeMovementCountIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AccountPosition(AccountId.generate(CREATED_AT), true, brl("0"), -1));
    }

    // --- OverviewState ---

    @Test
    void stateResolutionFollowsTheDataAvailable() {
        assertThat(OverviewState.resolve(0, false, 0)).isEqualTo(OverviewState.NO_ACCOUNTS);
        assertThat(OverviewState.resolve(0, true, 3)).isEqualTo(OverviewState.NO_ACCOUNTS);
        assertThat(OverviewState.resolve(2, false, 0)).isEqualTo(OverviewState.NO_TRANSACTIONS);
        assertThat(OverviewState.resolve(2, true, 0)).isEqualTo(OverviewState.NO_ACTIVITY_IN_PERIOD);
        assertThat(OverviewState.resolve(2, true, 4)).isEqualTo(OverviewState.READY);
    }

    @Test
    void onlyStatesWithHistoryHaveFinancialData() {
        assertThat(OverviewState.NO_ACCOUNTS.hasFinancialData()).isFalse();
        assertThat(OverviewState.NO_TRANSACTIONS.hasFinancialData()).isFalse();
        assertThat(OverviewState.NO_ACTIVITY_IN_PERIOD.hasFinancialData()).isTrue();
        assertThat(OverviewState.READY.hasFinancialData()).isTrue();
    }

    // --- MovementFlow ---

    @Test
    void flowDerivesFromTheBalanceContract() {
        assertThat(MovementFlow.of(TransactionType.INCOME, null)).isEqualTo(MovementFlow.INFLOW);
        assertThat(MovementFlow.of(TransactionType.REFUND, null)).isEqualTo(MovementFlow.INFLOW);
        assertThat(MovementFlow.of(TransactionType.EXPENSE, null)).isEqualTo(MovementFlow.OUTFLOW);
        assertThat(MovementFlow.of(TransactionType.ADJUSTMENT, AdjustmentDirection.INCREASE))
                .isEqualTo(MovementFlow.INFLOW);
        assertThat(MovementFlow.of(TransactionType.ADJUSTMENT, AdjustmentDirection.DECREASE))
                .isEqualTo(MovementFlow.OUTFLOW);
    }

    @Test
    void transferIsNeitherInflowNorOutflow() {
        assertThat(MovementFlow.of(TransactionType.TRANSFER, null)).isEqualTo(MovementFlow.TRANSFER);
    }

    private static AccountPosition position(boolean included, String balance) {
        return new AccountPosition(AccountId.generate(CREATED_AT), included, brl(balance), 0);
    }
}
