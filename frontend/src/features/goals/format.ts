import { ShoppingBag, Target, Umbrella, type LucideIcon } from 'lucide-react'
import type { Goal, GoalForecast, GoalStatus, GoalType, SimulationScenario } from './api'

/**
 * Textos e rótulos dos Objetivos (ADR-0037). Só apresentação: percentuais, meses, previsão e simulação chegam prontos
 * do backend (ADR-0034/0036); aqui nada é somado nem projetado.
 */

const MONTHS = ['jan', 'fev', 'mar', 'abr', 'mai', 'jun', 'jul', 'ago', 'set', 'out', 'nov', 'dez']
const LONG = ['janeiro', 'fevereiro', 'março', 'abril', 'maio', 'junho', 'julho', 'agosto', 'setembro', 'outubro', 'novembro', 'dezembro']
const MONTH = /^(\d{4})-(\d{2})/

/** "2027-07" → "jul/2027" (previsão, prazo e simulação, como no layout). */
export function monthYear(month: string | null | undefined): string {
  const match = month ? MONTH.exec(month) : null
  if (!match) return '—'
  return `${MONTHS[Number(match[2]) - 1] ?? match[2]}/${match[1]}`
}

/** "2027-07" → "julho de 2027" (texto falado e frases). */
export function longMonthYear(month: string | null | undefined): string {
  const match = month ? MONTH.exec(month) : null
  if (!match) return '—'
  return `${LONG[Number(match[2]) - 1] ?? match[2]} de ${match[1]}`
}

/** "2026-10" → "outubro" (toast do D-GoalCreated). */
export function monthName(month: string | null | undefined): string {
  const match = month ? MONTH.exec(month) : null
  return match ? (LONG[Number(match[2]) - 1] ?? '') : ''
}

/** "2026-04-12" → "abr/2026" ("Criado em"). */
export const monthOfDate = (iso: string) => monthYear(iso.slice(0, 7))

export const TYPE_LABEL: Record<GoalType, string> = {
  EMERGENCY_RESERVE: 'Reserva de emergência',
  PURCHASE: 'Compra',
  CUSTOM: 'Outro objetivo',
}

export const TYPE_ICON: Record<GoalType, LucideIcon> = {
  EMERGENCY_RESERVE: Umbrella,
  PURCHASE: ShoppingBag,
  CUSTOM: Target,
}

export const STATUS_LABEL: Record<GoalStatus, string> = {
  ACTIVE: 'Ativo',
  PAUSED: 'Pausado',
  COMPLETED: 'Concluído',
  ARCHIVED: 'Arquivado',
}

const months = (n: number) => `${n} ${n === 1 ? 'mês' : 'meses'}`

export type BadgeTone = 'neutral' | 'success' | 'warning'

/**
 * Badge de status do Goal Card: o status do objetivo quando não está ativo; senão, a relação da previsão (Estimado)
 * com o prazo. Sem previsão, não há badge de prazo (só fatos). `short` é o texto do M-Goals ("+1 mês").
 */
export function statusBadge(goal: Goal): { label: string; short: string; tone: BadgeTone; icon: 'clock' | 'alert' | 'check' } | null {
  if (goal.status === 'PAUSED') return { label: 'Pausado', short: 'Pausado', tone: 'neutral', icon: 'clock' }
  if (goal.status === 'COMPLETED') return { label: 'Concluído', short: 'Concluído', tone: 'success', icon: 'check' }
  if (goal.status === 'ARCHIVED') return { label: 'Arquivado', short: 'Arquivado', tone: 'neutral', icon: 'clock' }
  const f = goal.forecast
  switch (f.status) {
    case 'REACHED':
      return { label: 'Alvo atingido', short: 'Atingido', tone: 'success', icon: 'check' }
    case 'NO_DEADLINE':
      return { label: 'Sem prazo', short: 'Sem prazo', tone: 'neutral', icon: 'clock' }
    case 'LATE':
      return f.monthsLate
        ? { label: `${months(f.monthsLate)} após o prazo`, short: `+${months(f.monthsLate)}`, tone: 'warning', icon: 'alert' }
        : null
    case 'ON_TRACK':
      return { label: 'No prazo', short: 'No prazo', tone: 'success', icon: 'check' }
    default:
      return goal.deadline === null ? { label: 'Sem prazo', short: 'Sem prazo', tone: 'neutral', icon: 'clock' } : null
  }
}

/** Por que não há previsão (ADR-0036 §1): o card mostra só os fatos e esta frase. */
export function noForecastText(forecast: GoalForecast): string {
  switch (forecast.reason) {
    case 'NO_PLAN':
      return 'Defina um aporte mensal para ver a previsão.'
    case 'NOT_ACTIVE':
      return 'Sem previsão enquanto o objetivo não está ativo.'
    case 'TOO_FAR':
      return 'Com este aporte, a conclusão passa de 50 anos.'
    case 'FORECAST_NOT_READY':
    default:
      return 'A previsão aparece quando houver 3 meses de histórico.'
  }
}

export const CONFIDENCE_LABEL: Record<NonNullable<GoalForecast['confidence']>, string> = {
  HIGH: 'alta',
  MEDIUM: 'média',
  LOW: 'baixa',
}

/** "jul/2027 a set/2027" ou "a partir de jul/2027" (faixa sem fim no ritmo real). */
export function rangeText(forecast: GoalForecast): string | null {
  const range = forecast.range
  if (!range) return null
  if (range.openEnded || !range.pessimistic) return `a partir de ${monthYear(range.optimistic)}`
  if (range.pessimistic === range.optimistic) return null
  return `${monthYear(range.optimistic)} a ${monthYear(range.pessimistic)}`
}

/** Situação do cenário simulado frente ao prazo (Simulado). */
export function scenarioDeadlineText(scenario: SimulationScenario): { text: string; within: boolean } | null {
  const deadline = scenario.deadline
  if (!deadline || deadline.within === null) return null
  if (deadline.within) return { text: `Dentro do prazo de ${monthYear(deadline.month)}`, within: true }
  const late = deadline.monthsFromDeadline ?? 0
  return { text: `${months(Math.abs(late))} após o prazo de ${monthYear(deadline.month)}`, within: false }
}

export const contributionsText = (n: number) => `${n} ${n === 1 ? 'aporte' : 'aportes'}`
