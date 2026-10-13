'use client'

import Link from 'next/link'
import { useState } from 'react'
import type { AnalysisOverview, TransactionSummary } from '@/lib/api/types'
import { bffCall } from '@/lib/bff-client'
import { useKeyed } from '@/lib/use-keyed'
import { Money } from '@/components/ui/Money'
import { Skeleton } from '@/components/ui/Skeleton'
import { Waterfall } from '@/components/charts/Waterfall'
import { compositionSteps } from '@/features/analysis/tabs'
import { listSearch, type ListParams } from './api'
import styles from './PeriodSummary.module.css'

/**
 * Topo de D-Transactions (ADR-0016): Entradas, Saídas, Reembolsos, Transferências e Resultado do período filtrado,
 * calculados no backend com os mesmos filtros da lista. "Por que meu dinheiro mudou?" aparece só quando o filtro é um
 * mês inteiro sem outros filtros (`wholeMonth`); nos demais casos, um link para a análise.
 */
export function PeriodSummary({ params, wholeMonth, names }: {
  params: ListParams
  wholeMonth: string | null
  names: Map<string, string>
}) {
  const [attempt, setAttempt] = useState(0)
  const search = listSearch({ ...params, status: undefined })
  const summary = useKeyed<TransactionSummary>(`summary?${search}#${attempt}`, async () => {
    const result = await bffCall<TransactionSummary>(`/api/bff/transactions/summary${search ? `?${search}` : ''}`)
    return result.ok ? { status: 'success', data: result.data } : { status: 'error', failure: result }
  }).value

  return (
    <section className={styles.summary} aria-labelledby="period-summary-title">
      <h2 id="period-summary-title" className="sr-only">
        Resumo do período filtrado
      </h2>
      {summary.status === 'loading' ? (
        <div className={styles.metrics} role="status" aria-busy="true">
          <span className="sr-only">Calculando o resumo…</span>
          {[0, 1, 2, 3].map((i) => (
            <div key={i} className={styles.metric}>
              <Skeleton width="sm" height="caption" />
              <Skeleton width="md" height="title" />
            </div>
          ))}
        </div>
      ) : summary.status === 'error' ? (
        <p className={styles.partial} role="status">
          Não conseguimos calcular o resumo agora. A lista continua correta.{' '}
          <button type="button" className={styles.retry} onClick={() => setAttempt((n) => n + 1)}>
            Tentar novamente
          </button>
        </p>
      ) : (
        <dl className={styles.metrics}>
          <div className={styles.metric}>
            <dt className={styles.label}>Entradas</dt>
            <dd className={styles.value}><Money value={summary.data.income} sign="inflow" /></dd>
            {summary.data.refunds.amount !== '0.00' ? (
              <dd className={styles.hint}>+ <Money value={summary.data.refunds} /> em reembolsos</dd>
            ) : null}
          </div>
          <div className={styles.metric}>
            <dt className={styles.label}>Saídas</dt>
            <dd className={styles.value}><Money value={summary.data.expense} sign="outflow" /></dd>
          </div>
          <div className={styles.metric}>
            <dt className={styles.label}>Transferências</dt>
            <dd className={styles.value}><Money value={summary.data.transfers.volume} sign="never" /></dd>
            <dd className={styles.hint}>
              {summary.data.transfers.count === 1 ? '1 transferência' : `${summary.data.transfers.count} transferências`} · não
              contam como gasto
            </dd>
          </div>
          <div className={styles.metric}>
            <dt className={styles.label}>Resultado</dt>
            <dd className={styles.value}><Money value={summary.data.net} sign="always" /></dd>
            <dd className={styles.hint}>Entradas + reembolsos − saídas{summary.data.pendingCount > 0 ? ` · ${summary.data.pendingCount} pendente(s) fora da conta` : ''}</dd>
          </div>
        </dl>
      )}
      {wholeMonth ? <WhyMoneyChanged month={wholeMonth} names={names} /> : (
        <p className={styles.hintLine}>
          <Link className={styles.link} href="/analysis">Ver a análise do mês</Link>
        </p>
      )}
    </section>
  )
}

function WhyMoneyChanged({ month, names }: { month: string; names: Map<string, string> }) {
  const overview = useKeyed<AnalysisOverview>(`why?${month}`, async () => {
    const result = await bffCall<AnalysisOverview>(`/api/bff/analytics/overview?month=${month}`)
    return result.ok ? { status: 'success', data: result.data } : { status: 'error', failure: result }
  }).value
  if (overview.status !== 'success' || !overview.data.composition) return null
  return (
    <details className={styles.why}>
      <summary>Por que meu dinheiro mudou?</summary>
      <Waterfall label="Composição do resultado do mês" steps={compositionSteps(overview.data.composition, names)} />
      <p className={styles.hintLine}>
        <Link className={styles.link} href={`/analysis?month=${month}`}>Ver a análise completa</Link>
        {overview.data.freshness.state !== 'UP_TO_DATE' ? ' · a análise está sendo atualizada' : ''}
      </p>
    </details>
  )
}
