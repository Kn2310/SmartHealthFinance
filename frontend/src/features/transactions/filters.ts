import { STATUS_OPTIONS, TYPE_OPTIONS } from './labels'
import { businessToday, isIsoDate, monthRange } from '@/lib/format/business-date'
import { isUuid } from '@/lib/uuid'
import type { ListParams, TransactionStatus, TransactionType } from './api'

/** Período da lista. `CUSTOM` usa `from`/`to` (inclusivos, como no backend). */
export type PeriodPreset = 'CURRENT_MONTH' | 'PREVIOUS_MONTH' | 'ALL' | 'CUSTOM'

export const PERIOD_OPTIONS: readonly [PeriodPreset, string][] = [
  ['CURRENT_MONTH', 'Este mês'],
  ['PREVIOUS_MONTH', 'Mês anterior'],
  ['ALL', 'Todo o período'],
  ['CUSTOM', 'Personalizado'],
]

/**
 * Filtros que ficam na URL (compartilháveis e com "voltar" funcionando). A busca por texto NÃO fica na URL:
 * é trecho de descrição (dado do usuário) e vive só no estado da tela.
 */
export interface Filters {
  period: PeriodPreset
  from?: string
  to?: string
  accountId?: string
  type?: TransactionType
  status?: TransactionStatus
}

type Params = { get(name: string): string | null }

const TYPES = TYPE_OPTIONS.map(([value]) => value) as readonly string[]
const STATUSES = STATUS_OPTIONS.map(([value]) => value) as readonly string[]
const PRESETS = PERIOD_OPTIONS.map(([value]) => value) as readonly string[]

/** Lê os filtros da URL; valor desconhecido ou malformado cai no padrão (nunca vira erro). */
export function parseFilters(params: Params, defaultPeriod: PeriodPreset = 'CURRENT_MONTH'): Filters {
  const period = params.get('period')
  const from = params.get('from')
  const to = params.get('to')
  const accountId = params.get('accountId')
  const type = params.get('type')
  const status = params.get('status')
  const filters: Filters = { period: period && PRESETS.includes(period) ? (period as PeriodPreset) : defaultPeriod }
  if (filters.period === 'CUSTOM') {
    if (isIsoDate(from) && isIsoDate(to) && from <= to) Object.assign(filters, { from, to })
    else filters.period = defaultPeriod
  }
  if (isUuid(accountId)) filters.accountId = accountId
  if (type && TYPES.includes(type)) filters.type = type as TransactionType
  if (status && STATUSES.includes(status)) filters.status = status as TransactionStatus
  return filters
}

export function filtersToSearch(filters: Filters, defaultPeriod: PeriodPreset = 'CURRENT_MONTH'): string {
  const search = new URLSearchParams()
  if (filters.period !== defaultPeriod) search.set('period', filters.period)
  if (filters.period === 'CUSTOM' && filters.from && filters.to) {
    search.set('from', filters.from)
    search.set('to', filters.to)
  }
  if (filters.accountId) search.set('accountId', filters.accountId)
  if (filters.type) search.set('type', filters.type)
  if (filters.status) search.set('status', filters.status)
  return search.toString()
}

/** Datas do período no fuso de negócio (ADR-0006 §10). Só converte o atalho; quem filtra é o backend. */
export function periodRange(filters: Filters, today: string = businessToday()): { from?: string; to?: string } {
  switch (filters.period) {
    case 'CURRENT_MONTH':
      return monthRange(today)
    case 'PREVIOUS_MONTH':
      return monthRange(today, -1)
    case 'CUSTOM':
      return { from: filters.from, to: filters.to }
    default:
      return {}
  }
}

export function toListParams(filters: Filters, q: string, today?: string): ListParams {
  return {
    ...periodRange(filters, today),
    accountId: filters.accountId,
    type: filters.type,
    status: filters.status,
    q: q.trim() || undefined,
  }
}

/** Algum filtro além do período padrão? (decide entre "nenhuma encontrada" e "primeira vez"). */
export function isFiltered(filters: Filters, q: string, defaultPeriod: PeriodPreset, fixedAccount = false): boolean {
  return (
    filters.period !== defaultPeriod ||
    Boolean((!fixedAccount && filters.accountId) || filters.type || filters.status || q.trim())
  )
}
