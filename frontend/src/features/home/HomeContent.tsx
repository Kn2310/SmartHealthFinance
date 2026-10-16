import type { ReactNode } from 'react'
import type { Overview } from '@/lib/api/types'
import { AccountsSummary } from './AccountsSummary'
import { BalanceHero } from './BalanceHero'
import { CashFlowSummary } from './CashFlowSummary'
import { NoAccounts, NoActivityInPeriod, NoTransactions, UnsupportedState } from './HomeEmpty'
import { RecentTransactions } from './RecentTransactions'
import styles from './HomeView.module.css'

/**
 * Renderiza a Home a partir do `state` semântico do backend. Só apresenta: nenhum saldo ou fluxo é calculado aqui,
 * e `null` (sem dados) nunca é convertido em zero. `health` é o card "Como estou?" (ADR-0019), sempre o primeiro
 * bloco e independente do período da Visão geral.
 */
export function HomeContent({ overview, health = null }: { overview: Overview; health?: ReactNode }) {
  const healthBlock = health ? <div className={styles.health}>{health}</div> : null
  const { state, summary, period, cashFlow, accounts, recentTransactions } = overview

  switch (state) {
    case 'NO_ACCOUNTS':
      return (
        <div className={styles.stack}>
          {healthBlock}
          <NoAccounts />
        </div>
      )

    case 'NO_TRANSACTIONS':
      // Ainda não há nada lançado (POSTED), mas o backend pode devolver movimentações PENDING do período.
      // Elas aparecem como pendentes, separadas do realizado: não há saldo nem fluxo para mostrar.
      return (
        <div className={styles.stack}>
          {healthBlock}
          <NoTransactions accountCount={summary.accountCount} pendingTransactions={summary.pendingTransactions} />
          {recentTransactions.length > 0 ? (
            <RecentTransactions
              id="pending"
              title="Movimentações pendentes"
              subtitle="Ainda não entram no saldo nem no fluxo do período."
              transactions={recentTransactions}
            />
          ) : null}
          {accounts.length > 0 ? <AccountsSummary accounts={accounts} /> : null}
        </div>
      )

    case 'NO_ACTIVITY_IN_PERIOD':
      return (
        <div className={`${styles.grid} ${healthBlock ? styles.withHealth : ''}`}>
          {healthBlock}
          <div className={styles.hero}>
            <BalanceHero summary={summary} />
          </div>
          <div className={styles.cashflow}>
            <NoActivityInPeriod balanceAsOf={summary.balanceAsOf} />
          </div>
          <div className={styles.accounts}>
            <AccountsSummary accounts={accounts} />
          </div>
          <div className={styles.recent}>
            <RecentTransactions transactions={recentTransactions} />
          </div>
        </div>
      )

    case 'READY':
      return (
        <div className={`${styles.grid} ${healthBlock ? styles.withHealth : ''}`}>
          {healthBlock}
          <div className={styles.hero}>
            <BalanceHero summary={summary} />
          </div>
          <div className={styles.cashflow}>
            <CashFlowSummary cashFlow={cashFlow} period={period} />
          </div>
          <div className={styles.accounts}>
            <AccountsSummary accounts={accounts} />
          </div>
          <div className={styles.recent}>
            <RecentTransactions transactions={recentTransactions} />
          </div>
        </div>
      )

    default:
      // Estado novo do backend que esta versão não conhece: não inventa saldo nem quebra a tela.
      return <UnsupportedState />
  }
}
