import { businessToday } from '@/lib/format/business-date'

/** Abas da Análise (design/specs/03-screens, ANALYSIS) e a rota de cada uma. */
export const TABS = [
  { tab: 'overview', href: '/analysis', label: 'Visão geral' },
  { tab: 'spending', href: '/analysis/spending', label: 'Gastos' },
  { tab: 'income', href: '/analysis/income', label: 'Receitas' },
  { tab: 'categories', href: '/analysis/categories', label: 'Categorias' },
  { tab: 'merchants', href: '/analysis/merchants', label: 'Comerciantes' },
  { tab: 'evolution', href: '/analysis/evolution', label: 'Evolução' },
] as const
export type AnalysisTab = (typeof TABS)[number]['tab']

/** "Comparar com" (ADR-0015 §2): mês anterior ou média de 3/6 meses, sempre no mesmo recorte de dias. */
export const COMPARE_OPTIONS = [
  { value: 'PREVIOUS', label: 'Mês anterior' },
  { value: 'AVERAGE_3', label: 'Média de 3 meses' },
  { value: 'AVERAGE_6', label: 'Média de 6 meses' },
] as const
export type CompareOption = (typeof COMPARE_OPTIONS)[number]['value']

/** Estado da Análise na URL (compartilhável): mês e base de comparação. */
export interface AnalysisQuery {
  /** YYYY-MM; ausente = mês corrente. */
  month?: string
  compare: CompareOption
}

type Params = { get(name: string): string | null }

const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/

export function parseAnalysisQuery(params: Params): AnalysisQuery {
  const month = params.get('month')
  const compare = params.get('compare')
  return {
    month: month && MONTH.test(month) ? month : undefined,
    compare: COMPARE_OPTIONS.some((o) => o.value === compare) ? (compare as CompareOption) : 'PREVIOUS',
  }
}

/** Parâmetros da URL da página (só o que difere do padrão). */
export function toPageSearch(query: AnalysisQuery, extra: Record<string, string | undefined> = {}): string {
  const search = new URLSearchParams()
  if (query.month) search.set('month', query.month)
  if (query.compare !== 'PREVIOUS') search.set('compare', query.compare)
  for (const [key, value] of Object.entries(extra)) if (value) search.set(key, value)
  return search.toString()
}

/** Parâmetros do BFF/backend (`month`, `compareWith`, `averageMonths`). */
export function toApiSearch(query: AnalysisQuery, extra: Record<string, string | undefined> = {}): string {
  const search = new URLSearchParams()
  if (query.month) search.set('month', query.month)
  if (query.compare !== 'PREVIOUS') {
    search.set('compareWith', 'AVERAGE')
    search.set('averageMonths', query.compare === 'AVERAGE_3' ? '3' : '6')
  }
  for (const [key, value] of Object.entries(extra)) if (value) search.set(key, value)
  return search.toString()
}

/** Os 12 meses até o corrente (no fuso de negócio), do mais recente ao mais antigo. */
export function monthOptions(today: string = businessToday(), count = 12): string[] {
  const [year, month] = today.split('-').map(Number) as [number, number]
  const index = year * 12 + (month - 1)
  return Array.from({ length: count }, (_, i) => {
    const value = index - i
    return `${Math.floor(value / 12)}-${String((value % 12) + 1).padStart(2, '0')}`
  })
}
