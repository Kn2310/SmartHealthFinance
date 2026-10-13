import type { AnalysisMeta, AnalysisVariation } from '@/lib/api/types'
import { formatPeriodRange } from '@/lib/format/date'
import { longMonthLabel, monthLabel } from '@/components/charts/geometry'

/**
 * Textos da Análise. Tudo vem calculado do backend (ADR-0015): aqui só se troca ponto por vírgula, escolhe a frase
 * e o sinal. Nenhuma conta com valores.
 */

/** "14.1" → "14,1%"; "-8.0" → "−8%"; com sinal explícito no positivo. Zeros decimais caem ("32.0" → "32%"). */
export function formatPercent(percent: string | null, { signed = true } = {}): string | null {
  if (percent === null) return null
  const negative = percent.startsWith('-')
  const digits = percent.replace(/^[-+]/, '').replace(/\.0$/, '').replace('.', ',')
  if (/^0(,0)?$/.test(digits)) return '0%'
  return `${negative ? '−' : signed ? '+' : ''}${digits}%`
}

/** Participação "33.4" → "33,4%". */
export function formatShare(share: string | null): string | null {
  return formatPercent(share, { signed: false })
}

export type VariationBase = 'previous' | 'average'

/** Base da comparação por extenso: "1 a 22 de agosto" / "média de abr–ago". */
export function baseLabel(meta: AnalysisMeta, base: VariationBase): string {
  if (base === 'previous') {
    const previous = meta.comparison.previous
    if (!previous) return 'mês anterior'
    return meta.period.partial ? formatPeriodRange(previous.from, previous.to) : longMonthLabel(previous.from.slice(0, 7)).split(' de ')[0]!
  }
  const months = meta.comparison.average.months
  if (months.length === 0) return 'média'
  if (months.length === 1) return `média de ${monthLabel(months[0]!)}`
  return `média de ${monthLabel(months[0]!)}–${monthLabel(months[months.length - 1]!)}`
}

/**
 * Frase da variação: "+14,1% vs 1 a 22 de agosto", "Estável vs média de abr–ago", "Novo vs …", "Zerou vs …".
 * Nula quando não há base (o componente mostra que não há comparação).
 */
export function variationText(variation: AnalysisVariation | null, meta: AnalysisMeta, base: VariationBase): string | null {
  if (!variation) return null
  const reference = `vs ${baseLabel(meta, base)}`
  switch (variation.trend) {
    case 'STABLE':
      return `Estável ${reference}`
    case 'NEW':
      return `Novo ${reference}`
    case 'GONE':
      return `Zerou ${reference}`
    default:
      return `${formatPercent(variation.percent)} ${reference}`
  }
}

/** Cabeçalho: "Setembro de 2026 (1 a 22) comparado a 1 a 22 de agosto". */
export function periodHeading(meta: AnalysisMeta): string {
  const month = longMonthLabel(meta.period.month)
  const title = month.charAt(0).toUpperCase() + month.slice(1)
  const range = meta.period.partial ? ` (1 a ${meta.period.daysElapsed})` : ''
  const base = meta.comparison.selected === 'AVERAGE' ? 'average' : 'previous'
  const available = base === 'previous' ? meta.comparison.previous !== null : meta.comparison.average.monthsUsed > 0
  return available ? `${title}${range} comparado a ${baseLabel(meta, base)}` : `${title}${range}`
}
