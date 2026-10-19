import type { InsightAction, InsightConfidence, InsightFigure, InsightItem } from '@/lib/api/types'
import { formatMoney } from '@/lib/format/money'

/**
 * Rota de cada ação (ADR-0021 §2): o backend manda o código e os parâmetros, a UI só monta o link para telas que já
 * existem. Placeholders "Em breve" (`available = false`) e códigos desconhecidos não têm rota.
 */
export function actionHref(action: InsightAction): string | null {
  if (!action.available) return null
  const p = action.params
  const search = (entries: Record<string, string | undefined>) => {
    const params = new URLSearchParams()
    for (const [key, value] of Object.entries(entries)) if (value) params.set(key, value)
    const query = params.toString()
    return query ? `?${query}` : ''
  }
  switch (action.kind) {
    case 'VIEW_TRANSACTIONS':
      return `/transactions${search({ period: p.from && p.to ? 'CUSTOM' : undefined, from: p.from, to: p.to, type: p.type, category: p.category })}`
    case 'VIEW_CATEGORY':
      return `/analysis/categories${search({ month: p.month, category: p.category })}`
    case 'VIEW_ANALYSIS':
      return `/analysis${search({ month: p.month })}`
    case 'VIEW_MERCHANTS':
      return `/analysis/merchants${search({ month: p.month })}`
    case 'VIEW_TRANSACTION':
      return p.transactionId ? `/transactions/${encodeURIComponent(p.transactionId)}` : null
    case 'IMPORT_STATEMENT':
      return '/import'
    case 'VIEW_INCOME':
      return `/analysis/income${search({ month: p.month })}`
    case 'VIEW_HEALTH':
      return '/home/health'
    case 'VIEW_ACCOUNTS':
      return '/accounts'
    case 'CATEGORIZE':
      return `/transactions${search({ category: p.category ?? 'none' })}`
    default:
      return null
  }
}

/** Meta do card: "Hoje", "Ontem" ou "20 set" (data da última atualização, no fuso do navegador). */
export function dayLabel(iso: string, now: Date = new Date()): string {
  const date = new Date(iso)
  const day = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()
  const diff = Math.round((day(now) - day(date)) / 86_400_000)
  if (diff === 0) return 'Hoje'
  if (diff === 1) return 'Ontem'
  return date.toLocaleDateString('pt-BR', { day: 'numeric', month: 'short' }).replace('.', '')
}

const CONFIDENCE: Record<InsightConfidence, { level: number; label: string }> = {
  HIGH: { level: 3, label: 'Confiança alta' },
  MEDIUM: { level: 2, label: 'Confiança média' },
  LOW: { level: 1, label: 'Confiança baixa' },
}

export function confidence(value: InsightConfidence): { level: number; label: string } {
  return CONFIDENCE[value] ?? { level: 1, label: 'Confiança baixa' }
}

/** Valor de um número-chave na unidade dele; dinheiro na moeda do Workspace. */
export function figureValue(figure: InsightFigure, currency = 'BRL'): string {
  const n = Number(figure.value)
  switch (figure.kind) {
    case 'MONEY':
      return formatMoney({ amount: figure.value, currency }) ?? figure.value
    case 'PERCENT':
      return Number.isFinite(n) ? `${n > 0 ? '+' : ''}${n.toLocaleString('pt-BR', { maximumFractionDigits: 1 })}%` : figure.value
    case 'MONTHS':
      return Number.isFinite(n) ? `${n.toLocaleString('pt-BR', { maximumFractionDigits: 1 })} meses` : figure.value
    case 'RATIO':
      return Number.isFinite(n) ? `${n.toLocaleString('pt-BR', { minimumFractionDigits: 1, maximumFractionDigits: 1 })}×` : figure.value
    case 'POINTS':
    case 'COUNT':
    default:
      return Number.isFinite(n) ? n.toLocaleString('pt-BR') : figure.value
  }
}

const SOURCE_NAME: Record<string, string> = {
  'analytics.categories': 'Análise › categorias',
  'analytics.spending': 'Análise › gastos',
  'analytics.income-sources': 'Análise › receitas',
  'analytics.merchants': 'Análise › comerciantes',
  'transactions.largest-expenses': 'Transações › maiores gastos',
  'health.snapshot': 'Saúde financeira',
  'overview.account-balances': 'Contas › saldos',
}

export function sourceName(source: string): string {
  return SOURCE_NAME[source] ?? source
}

const DATA_SOURCE: Record<string, string> = { CSV: 'CSV', MANUAL: 'manual', OFX: 'OFX' }

/** "OFX, CSV e manual" — origens dos lançamentos usados. */
export function dataSourcesText(sources: string[]): string | null {
  const names = sources.map((s) => DATA_SOURCE[s] ?? s)
  if (names.length === 0) return null
  if (names.length === 1) return names[0] ?? null
  return `${names.slice(0, -1).join(', ')} e ${names[names.length - 1]}`
}

/** Nome acessível do card: tipo, importância e a frase (o card inteiro é um link). */
export function cardName(item: InsightItem): string {
  return `${item.typeLabel}, ${item.importance === 'IMPORTANT' ? 'importante' : 'informativo'}: ${item.title}`
}
