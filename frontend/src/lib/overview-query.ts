import type { PeriodType } from '@/lib/api/types'

export interface OverviewQuery {
  period: PeriodType
  from?: string
  to?: string
}

export const DEFAULT_QUERY: OverviewQuery = { period: 'CURRENT_MONTH' }

const PERIODS: readonly PeriodType[] = ['CURRENT_MONTH', 'PREVIOUS_MONTH', 'CUSTOM']

type Params = { get(name: string): string | null }

/** Lê o período de uma query string; valores desconhecidos caem no padrão (CURRENT_MONTH). */
export function parseOverviewQuery(params: Params): OverviewQuery {
  const period = params.get('period') as PeriodType | null
  if (!period || !PERIODS.includes(period)) return DEFAULT_QUERY
  if (period !== 'CUSTOM') return { period }
  const from = params.get('from')
  const to = params.get('to')
  return from && to ? { period, from, to } : DEFAULT_QUERY
}

export function toSearchParams(query: OverviewQuery): URLSearchParams {
  const params = new URLSearchParams()
  if (query.period === 'CURRENT_MONTH') return params
  params.set('period', query.period)
  if (query.period === 'CUSTOM' && query.from && query.to) {
    params.set('from', query.from)
    params.set('to', query.to)
  }
  return params
}
