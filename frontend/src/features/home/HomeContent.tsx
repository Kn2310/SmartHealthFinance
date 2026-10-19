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
 * bloco e independente do período da Visão geral; `insights` são "Merece atenção" e "O que mudou?" (ADR-0023 §5),
 * logo depois da Visão geral, só nos estados com saldo (os blocos têm os próprios estados e não seguem o período).
 */
export function HomeContent({ overview, health = null, insights = null }: {
  overview: Overview
  health?: ReactNode
  insights?: { changes: ReactNode; attention: ReactNode } | null
}) {
  const healthBlock = health ? <div className={styles.health}>{health}</div> : null
  const insightBlocks = insights ? (
    <>
      <div className={styles.attention}>{insights.attention}</div>
      <div className={styles.changes}>{insights.changes}</div>
    </>
  ) : null
  const gridClass = [styles.grid, healthBlock ? styles.withHealth : '', insightBlocks ? styles.withInsights : '']
    .filter(Boolean).join(' ')
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
        <div className={gridClass}>
          {healthBlock}
          <div className={styles.hero}>
            <BalanceHero summary={summary} />
          </div>
          <div className={styles.cashflow}>
            <NoActivityInPeriod balanceAsOf={summary.balanceAsOf} />
          </div>
          {insightBlocks}
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
        <div className={gridClass}>
          {healthBlock}
          <div className={styles.hero}>
            <BalanceHero summary={summary} />
          </div>
          <div className={styles.cashflow}>
            <CashFlowSummary cashFlow={cashFlow} period={period} />
          </div>
          {insightBlocks}
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
