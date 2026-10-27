import { longMonthLabel, monthLabel } from '@/components/charts/geometry'
import type { Card, InstallmentPlan, InvoiceSummary } from './api'

export { cardLabel } from '@/lib/format/card'

/**
 * Rótulos das telas de Cartões a partir dos enums do contrato. Estados, valores e percentuais vêm prontos do backend
 * (ADR-0025 §12); aqui só há texto.
 */

type Tone = 'info' | 'success' | 'neutral' | 'warning' | 'teal'

const INVOICE_STATUS: Record<InvoiceSummary['status'], { label: string; tone: Tone }> = {
  FUTURE: { label: 'Futura', tone: 'neutral' },
  OPEN: { label: 'Aberta', tone: 'info' },
  CLOSED: { label: 'Fechada', tone: 'neutral' },
  PARTIALLY_PAID: { label: 'Paga em parte', tone: 'neutral' },
  PAID: { label: 'Paga', tone: 'success' },
  OVERDUE: { label: 'Vencida', tone: 'warning' },
}

export const invoiceStatus = (status: InvoiceSummary['status']) =>
  INVOICE_STATUS[status] ?? { label: 'Fatura', tone: 'neutral' as Tone }

type InstallmentStatus = InstallmentPlan['installments'][number]['status']

const INSTALLMENT_STATUS: Record<InstallmentStatus, string> = {
  PAID: 'Paga',
  CURRENT: 'Nesta fatura',
  FUTURE: 'Futura',
  CANCELLED: 'Cancelada',
  // Texto curto aprovado (ADR-0029 §4): fatura anterior ao início do acompanhamento (ADR-0025 §15).
  BEFORE_TRACKING: 'Antes do acompanhamento',
}

export const installmentStatusLabel = (status: InstallmentStatus) => INSTALLMENT_STATUS[status] ?? status

const PLAN_STATUS: Record<InstallmentPlan['status'], { label: string; tone: Tone }> = {
  ACTIVE: { label: 'Em andamento', tone: 'info' },
  FINISHED: { label: 'Concluído', tone: 'success' },
  CANCELLED: { label: 'Cancelado', tone: 'neutral' },
}

export const planStatus = (status: InstallmentPlan['status']) =>
  PLAN_STATUS[status] ?? { label: 'Parcelamento', tone: 'neutral' as Tone }

/** "outubro" (ou "outubro de 2027" fora do ano de referência). */
export function monthName(month: string, referenceYear?: string): string {
  const long = longMonthLabel(month)
  return referenceYear && !month.startsWith(referenceYear) ? long : long.split(' de ')[0]!
}

/** "Fatura de outubro" pelo mês de vencimento (ADR-0025 §5). */
export const invoiceName = (dueMonth: string, referenceYear?: string) => `Fatura de ${monthName(dueMonth, referenceYear)}`

/** "Fatura de outubro de 2026": listas com meses de anos diferentes. */
export const invoiceFullName = (dueMonth: string) => `Fatura de ${longMonthLabel(dueMonth)}`

/** "out/26". */
export const shortMonth = (month: string) => monthLabel(month, true)

/** "fecha dia 28 · vence dia 05". */
export const cycleText = (card: Pick<Card, 'closingDay' | 'dueDay'>) =>
  `fecha dia ${String(card.closingDay).padStart(2, '0')} · vence dia ${String(card.dueDay).padStart(2, '0')}`

/** Versão das regras do cálculo, mostrada onde há valor derivado (ADR-0025 §5, handoff da 6.1). */
export const calculationNote = (version: string) => `Calculado pelo app · regras ${version}`

/** Texto curto aprovado para a primeira fatura acompanhada (ADR-0029 §4; ADR-0025 §15). */
export const TRACKING_START_NOTE = 'Primeira fatura acompanhada pelo app. Faturas e pagamentos anteriores não entram aqui.'

export const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`
