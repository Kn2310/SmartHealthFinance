import type { Health, HealthDimensionCode, HealthEvidence, HealthNextStep, InsightItem } from '@/lib/api/types'
import { formatMoney } from '@/lib/format/money'
import { formatPeriodRange, formatShortDate } from '@/lib/format/date'
import { longMonthLabel, monthLabel } from '@/components/charts/geometry'

/**
 * Formatação do Health (ADR-0019). Só apresenta: score, variação, textos e números vêm prontos do backend; aqui eles
 * ganham rótulo, unidade e destino de link. Nenhuma regra de cálculo.
 */

/** Nomes curtos do layout (D-Home / M-Home), na ordem do backend. */
export const DIMENSION_SHORT: Record<HealthDimensionCode, string> = {
  CASH_FLOW: 'Cash Flow',
  SPENDING: 'Spending',
  CREDIT: 'Credit',
  DEBT: 'Debt',
  RESERVE: 'Reserve',
  CONSISTENCY: 'Consistency',
}

export const CONFIDENCE_LABEL: Record<NonNullable<Health['confidence']>, string> = {
  HIGH: 'alta',
  MEDIUM: 'média',
  LOW: 'baixa',
}

const SOURCE_LABEL: Record<string, string> = { OFX: 'OFX', CSV: 'CSV', MANUAL: 'entrada manual' }
const SOURCE_ORDER = ['OFX', 'CSV', 'MANUAL']

/** "OFX, CSV e entrada manual" (ordem do layout); vazio = nulo. */
export function sourcesText(sources: readonly string[]): string | null {
  const names = SOURCE_ORDER.filter((s) => sources.includes(s)).map((s) => SOURCE_LABEL[s]!)
  if (names.length === 0) return null
  return names.length === 1 ? names[0]! : `${names.slice(0, -1).join(', ')} e ${names.at(-1)}`
}

function capitalize(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1)
}

/** "2026-09" → "Setembro de 2026". */
export function monthTitle(month: string): string {
  return capitalize(longMonthLabel(month))
}

/** Mês anterior em extenso curto: "2026-09" → "agosto". */
export function previousMonthName(month: string): string {
  const [year, m] = month.split('-').map(Number) as [number, number]
  const prev = m === 1 ? `${year - 1}-12` : `${year}-${String(m - 1).padStart(2, '0')}`
  return longMonthLabel(prev).split(' de ')[0]!
}

/** Variação neutra (07 §3): "+3 pontos vs agosto", "−2 pontos vs agosto", "Igual a agosto". */
export function variationShort(variation: number | null, month: string): string | null {
  if (variation === null) return null
  const prev = previousMonthName(month)
  if (variation === 0) return `Igual a ${prev}`
  const points = Math.abs(variation) === 1 ? 'ponto' : 'pontos'
  return `${variation > 0 ? '+' : '−'}${Math.abs(variation)} ${points} vs ${prev}`
}

/** Manchete da Home: "Seu score subiu 3 pontos desde agosto". */
export function variationHeadline(variation: number | null, month: string): string | null {
  if (variation === null) return null
  const prev = previousMonthName(month)
  if (variation === 0) return `Seu score se manteve desde ${prev}`
  const points = Math.abs(variation) === 1 ? 'ponto' : 'pontos'
  return `Seu score ${variation > 0 ? 'subiu' : 'caiu'} ${Math.abs(variation)} ${points} desde ${prev}`
}

/** "abr–set" (janela de meses fechados até a data-base). */
export function basisText(health: Health): string | null {
  const from = health.period.windowFrom?.slice(0, 7)
  const to = health.period.month
  if (!from) return monthLabel(to)
  return from === to ? monthLabel(to) : `${monthLabel(from)}–${monthLabel(to)}`
}

/** "há 12 min", "agora há pouco", "há 3 h", "em 14/10". */
export function updatedAgo(iso: string | null, now: Date = new Date()): string | null {
  if (!iso) return null
  const minutes = Math.floor((now.getTime() - new Date(iso).getTime()) / 60_000)
  if (!Number.isFinite(minutes)) return null
  if (minutes < 1) return 'agora há pouco'
  if (minutes < 60) return `há ${minutes} min`
  if (minutes < 24 * 60) return `há ${Math.floor(minutes / 60)} h`
  const date = new Date(iso)
  return `em ${String(date.getDate()).padStart(2, '0')}/${String(date.getMonth() + 1).padStart(2, '0')}`
}

/** Valor de uma evidência com a unidade do tipo; dinheiro na moeda da resposta. */
export function evidenceValue(evidence: HealthEvidence, currency: string): string {
  switch (evidence.kind) {
    case 'MONEY':
      return formatMoney({ amount: evidence.value, currency }) ?? evidence.value
    case 'PERCENT':
      return `${decimal(evidence.value, 1)}%`
    case 'MONTHS':
      return `${decimal(evidence.value, 1)} meses`
    case 'COUNT':
      return decimal(evidence.value, 0)
  }
}

/** Só formatação: o texto decimal exato do backend vira pt-BR com as casas pedidas. */
function decimal(value: string, digits: number): string {
  const n = Number(value)
  if (!Number.isFinite(n)) return value
  return n.toLocaleString('pt-BR', { minimumFractionDigits: digits, maximumFractionDigits: digits })
}

export function evidencePeriod(evidence: HealthEvidence): string {
  return evidence.from === evidence.to ? `em ${formatShortDate(evidence.from)}` : formatPeriodRange(evidence.from, evidence.to)
}

const SOURCE_NAME: Record<string, string> = {
  'analytics.monthly': 'Análise › fluxo mensal',
  'analytics.categories': 'Análise › categorias',
  'analytics.income-sources': 'Análise › fontes de receita',
  'overview.account-balances': 'Contas › saldos',
  'cards.utilization': 'Cartões › utilização',
  'cards.invoices': 'Cartões › faturas',
  'cards.installments': 'Cartões › parcelamentos',
}

export function evidenceSource(source: string): string {
  return SOURCE_NAME[source] ?? source
}

/**
 * Destino de cada próximo passo: só telas que existem (ADR-0017 §7, sem ✦). O passo da categoria leva ao insight da
 * alta dela no mesmo mês quando existe (adendo de 2026-10-19 ao ADR-0020); senão, à categoria na Análise. Fatura
 * vencida e utilização (ADR-0027 §4) levam às telas de Cartões (Parte F, adendo ao ADR-0020).
 *
 * @param insights insights ativos ligados aos gastos (`/insights/related?dimension=SPENDING`)
 */
export function nextStepLink(step: HealthNextStep, insights: InsightItem[] = []): { href: string | null; label: string } {
  switch (step.kind) {
    case 'PAY_OVERDUE_INVOICE':
      return {
        href: step.cardId && step.month ? `/cards/${encodeURIComponent(step.cardId)}/invoices/${encodeURIComponent(step.month)}` : null,
        label: 'Ver fatura',
      }
    case 'REVIEW_CARD_UTILIZATION':
      return { href: step.cardId ? `/cards/${encodeURIComponent(step.cardId)}` : null, label: 'Ver cartão' }
    case 'BUILD_RESERVE':
      return { href: '/accounts', label: 'Ver contas' }
    case 'ADD_RESERVE_ACCOUNT':
      return { href: '/accounts/new', label: 'Adicionar conta' }
    case 'REVIEW_CATEGORY': {
      const spike = insights.find((i) => i.detector === 'CATEGORY_SPIKE' && step.categoryCode
        && i.primaryAction.params.category === step.categoryCode && i.period.month === step.month)
      if (spike) return { href: `/insights/${spike.id}`, label: 'Ver insight' }
      const search = new URLSearchParams()
      if (step.month) search.set('month', step.month)
      if (step.categoryCode) search.set('category', step.categoryCode)
      return { href: `/analysis/categories?${search}`, label: 'Ver categoria' }
    }
    case 'CATEGORIZE_TRANSACTIONS':
      return { href: '/transactions?category=none', label: 'Categorizar transações' }
    case 'REVIEW_MONTH':
      return { href: step.month ? `/analysis?month=${step.month}` : '/analysis', label: 'Ver análise do mês' }
  }
}

/**
 * Eixo x das curvas do método: meses na Reserve e no horizonte do Debt (o eixo diz "meses"), percentual nas demais
 * (0.1 → "10%"). Só exibição.
 */
export function curvePoint(dimension: HealthDimensionCode, x: string, input = ''): string {
  const n = Number(x)
  if (!Number.isFinite(n)) return x
  if (dimension === 'RESERVE' || /meses/.test(input)) return `${n.toLocaleString('pt-BR')} ${n === 1 ? 'mês' : 'meses'}`
  return `${(Math.round(n * 1000) / 10).toLocaleString('pt-BR')}%`
}
