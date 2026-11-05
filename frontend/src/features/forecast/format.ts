import type {
  ForecastConfidence,
  ForecastDataState,
  ForecastFreshness,
  ForecastLimitation,
  ForecastValue,
} from '@/lib/api/types'

const CONFIDENCE: Record<ForecastConfidence, string> = {
  HIGH: 'confiança alta',
  MEDIUM: 'confiança média',
  LOW: 'confiança baixa',
}

/** Frases curtas das limitações (ADR-0030 §5); o código nunca aparece para a pessoa. */
const LIMITATION: Record<ForecastLimitation, string> = {
  INSUFFICIENT_HISTORY: 'Histórico ainda insuficiente.',
  SHORT_HISTORY: 'Histórico curto: a estimativa pode mudar.',
  HIGH_VARIABILITY: 'Seus valores variam bastante.',
  UNCATEGORIZED: 'Parte dos gastos está sem categoria.',
  PARTIAL_CARD_HISTORY: 'Poucas faturas acompanhadas neste cartão.',
}

export function limitationsText(limitations: ForecastLimitation[]): string | null {
  const phrases = limitations.map((code) => LIMITATION[code]).filter(Boolean)
  return phrases.length > 0 ? phrases.join(' ') : null
}

/** Meses de base de uma estimativa: as evidências mensais da resposta (amostras e faturas-base). */
export function evidenceMonths(value: ForecastValue): number {
  return value.evidence.filter((e) => e.kind === 'SAMPLE_MONTH' || e.kind === 'BASE_INVOICE').length
}

/** "pelo seu padrão dos últimos 3 meses" (1 mês: "do último mês"; sem base: só "pelo seu padrão"). */
export function patternPhrase(months: number): string {
  if (months <= 0) return 'pelo seu padrão'
  return months === 1 ? 'pelo seu padrão do último mês' : `pelo seu padrão dos últimos ${months} meses`
}

/** "confiança média · pelo seu padrão dos últimos 3 meses"; `basis` troca a segunda parte ("pelo seu histórico"). */
export function confidenceLine(value: ForecastValue, months: number, basis?: string): string {
  return `${CONFIDENCE[value.confidence]} · ${basis ?? patternPhrase(months)}`
}

/** O que a tela deve mostrar a partir do envelope (ADR-0032 §1): o estado de dados vence a freshness. */
export type EstimateStatus = 'READY' | 'RECALCULATING' | 'NOT_BUILT' | 'INSUFFICIENT_HISTORY' | 'NO_DATA'

export function estimateStatus(state: ForecastDataState, freshness: ForecastFreshness): EstimateStatus {
  if (state === 'NOT_BUILT' || freshness.state === 'NOT_BUILT') return 'NOT_BUILT'
  if (state === 'INSUFFICIENT_HISTORY') return 'INSUFFICIENT_HISTORY'
  if (state === 'NO_DATA') return 'NO_DATA'
  return freshness.state === 'RECALCULATING' ? 'RECALCULATING' : 'READY'
}

/** Só o snapshot de fatos: não há estimativa a mostrar. */
export const hasNoEstimates = (status: EstimateStatus) =>
  status === 'NOT_BUILT' || status === 'INSUFFICIENT_HISTORY' || status === 'NO_DATA'

export const NOTE_IN_PREPARATION = 'Estimativas em preparação.'
export const NOTE_INSUFFICIENT_HISTORY = 'As estimativas aparecem depois de 3 meses de histórico.'
export const NOTE_RECALCULATING = 'Atualizando estimativas…'

/** Nota curta do estado, ou `null` quando as estimativas estão prontas. */
export function statusNote(status: EstimateStatus): string | null {
  switch (status) {
    case 'RECALCULATING':
      return NOTE_RECALCULATING
    case 'NOT_BUILT':
      return NOTE_IN_PREPARATION
    case 'INSUFFICIENT_HISTORY':
      return NOTE_INSUFFICIENT_HISTORY
    default:
      return null
  }
}
