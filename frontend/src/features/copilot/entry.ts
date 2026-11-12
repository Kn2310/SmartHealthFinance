import { isUuid } from '@/lib/uuid'
import type { EntryContext } from './api'

/**
 * Contexto de entrada na URL (ADR-0041 §1, ADR-0040 §3): só tipo e ids, nunca pergunta ou valor. Parâmetro mal
 * formado, ausente ou de outro tipo é ignorado: a tela abre como o `/copilot` simples.
 */
export type EntryKind = 'insight' | 'invoice' | 'installment' | 'goal'

export interface Entry {
  kind: EntryKind
  context: EntryContext
}

const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/

type Search = Record<string, string | string[] | undefined>

const one = (value: string | string[] | undefined) => (typeof value === 'string' ? value : undefined)

export function parseEntry(search: Search): Entry | null {
  const from = one(search.from)
  const insightId = one(search.insightId)
  const cardId = one(search.cardId)
  const month = one(search.month)
  const planId = one(search.planId)
  const goalId = one(search.goalId)
  switch (from) {
    case 'insight':
      return isUuid(insightId) ? { kind: 'insight', context: { kind: 'INSIGHT', insightId } } : null
    case 'invoice':
      return isUuid(cardId) && month && MONTH.test(month)
        ? { kind: 'invoice', context: { kind: 'INVOICE', cardId, month } }
        : null
    case 'installment':
      return isUuid(cardId) && isUuid(planId)
        ? { kind: 'installment', context: { kind: 'INSTALLMENT_PLAN', cardId, planId } }
        : null
    case 'goal':
      return isUuid(goalId) ? { kind: 'goal', context: { kind: 'GOAL', goalId } } : null
    default:
      return null
  }
}

/** Rota de volta ao ponto de entrada, usada como caminho alternativo quando a análise falha. */
export function entryHref(entry: Entry | null): { href: string; label: string } {
  const context = entry?.context
  switch (context?.kind) {
    case 'INSIGHT':
      return { href: `/insights/${context.insightId}`, label: 'Ver insight' }
    case 'INVOICE':
      return { href: `/cards/${context.cardId}/invoices/${context.month}`, label: 'Ver fatura' }
    case 'INSTALLMENT_PLAN':
      return { href: `/cards/${context.cardId}/installments/${context.planId}`, label: 'Ver parcelamento' }
    case 'GOAL':
      return { href: `/goals/${context.goalId}`, label: 'Ver objetivo' }
    default:
      return { href: '/insights', label: 'Ver insights' }
  }
}
