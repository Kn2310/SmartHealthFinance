'use client'

import Link from 'next/link'
import type { ReactNode } from 'react'
import { Card } from '@/components/ui/Card'
import { Money } from '@/components/ui/Money'
import { Skeleton } from '@/components/ui/Skeleton'
import { longMonthLabel } from '@/components/charts/geometry'
import { useForecastMonth, useForecastNextIncome } from '@/features/forecast/api'
import { estimateStatus, hasNoEstimates, statusNote } from '@/features/forecast/format'
import { ProjectedBlock, StatusNote } from '@/features/forecast/parts'
import { formatShortDate } from '@/lib/format/date'
import styles from './Analysis.module.css'

function RecurringLink({ tab }: { tab: 'expenses' | 'incomes' }) {
  return (
    <Link className={styles.link} href={`/recurring?tab=${tab}`}>
      Ver recorrências
    </Link>
  )
}

function Loading({ label }: { label: string }) {
  return (
    <div role="status" aria-busy="true" className={styles.projection}>
      <span className="sr-only">Carregando {label}…</span>
      <Skeleton width="md" height="value" />
      <Skeleton width="lg" />
    </div>
  )
}

function Unavailable({ onRetry }: { onRetry: () => void }) {
  return (
    <div role="alert" className={styles.projection}>
      <p className={styles.muted}>Não conseguimos carregar a estimativa agora.</p>
      <button type="button" className={styles.link} onClick={onRetry}>
        Tentar novamente
      </button>
    </div>
  )
}

/**
 * "Projeção para o mês" de Gastos (ADR-0033 §3; preenche o placeholder adiado pelo ADR-0016): despesa projetada do mês
 * corrente com faixa, base e o realizado (fato) visível. A projeção é do mês corrente: em outro mês, o card só avisa.
 */
export function SpendingProjection({ month }: { month: string }) {
  const forecast = useForecastMonth()
  const common = { id: 'analysis-projection', title: 'Projeção para o mês', action: <RecurringLink tab="expenses" /> }

  let body: ReactNode
  if (forecast.status === 'loading') body = <Loading label="a projeção" />
  else if (forecast.status === 'error' || !forecast.data) body = <Unavailable onRetry={forecast.retry} />
  else {
    const { data } = forecast
    const status = estimateStatus(data.state, data.freshness)
    const projected = data.month
    if (projected && projected.month !== month) {
      body = <p className={styles.muted}>A projeção vale só para o mês corrente.</p>
    } else if (status === 'NO_DATA') {
      body = <p className={styles.muted}>A projeção aparece quando houver movimentações lançadas.</p>
    } else if (!projected) {
      body = <StatusNote status={status} />
    } else if (hasNoEstimates(status) || projected.baseMonths === 0) {
      // Só os fatos: o realizado, sem número projetado, e a nota do estado.
      body = (
        <div className={styles.projection}>
          <span className={styles.metricHint}>Gasto até agora</span>
          <span className={styles.metricValue}><Money value={projected.expense.factPart} /></span>
          <StatusNote status={status === 'READY' ? 'INSUFFICIENT_HISTORY' : status} />
        </div>
      )
    } else {
      body = (
        <div className={styles.projection}>
          <span className={styles.metricValue}>
            <ProjectedBlock value={projected.expense} months={projected.baseMonths} />
          </span>
          <span className={styles.metricHint}>
            Realizado até agora: <Money value={projected.expense.factPart} />
          </span>
          <StatusNote status={status} />
        </div>
      )
    }
  }

  return (
    <Card {...common} subtitle={capitalize(longMonthLabel(month))}>
      {body}
    </Card>
  )
}

/**
 * "Próxima receita prevista" de Receitas (ADR-0033 §3): fonte, data, "pelo seu histórico" e faixa. Sem receita
 * recorrente, "Sem receita recorrente identificada".
 */
export function NextIncomeCard() {
  const forecast = useForecastNextIncome()
  const common = { id: 'analysis-next-income', title: 'Próxima receita prevista', action: <RecurringLink tab="incomes" /> }

  let body: ReactNode
  if (forecast.status === 'loading') body = <Loading label="a próxima receita" />
  else if (forecast.status === 'error' || !forecast.data) body = <Unavailable onRetry={forecast.retry} />
  else {
    const { data } = forecast
    const status = estimateStatus(data.state, data.freshness)
    const next = data.nextIncome
    if (status === 'NOT_BUILT' || status === 'INSUFFICIENT_HISTORY') {
      body = <p className={styles.muted}>{statusNote(status)}</p>
    } else if (!next) {
      body = <p className={styles.muted}>Sem receita recorrente identificada</p>
    } else {
      body = (
        <div className={styles.projection}>
          <span className={styles.itemTitle}>
            {next.label ?? 'Receita recorrente'} · {formatShortDate(next.date)}
          </span>
          <span className={styles.metricValue}>
            <ProjectedBlock value={next.amount} months={0} basis="pelo seu histórico" />
          </span>
          <StatusNote status={status} />
        </div>
      )
    }
  }

  return (
    <Card {...common} subtitle="Pelo seu histórico">
      {body}
    </Card>
  )
}

function capitalize(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1)
}
