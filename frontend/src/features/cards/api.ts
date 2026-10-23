import type { components } from '@/lib/api/schema'
import { bffCall } from '@/lib/bff-client'

type Schemas = components['schemas']
export type Card = Schemas['CardResponse']
export type CardBrand = Card['brand']

/**
 * Cadastro mínimo do cartão como o BFF espera (ADR-0026 §2): limite como string decimal, dias como inteiros e só os
 * 4 últimos dígitos. Número completo, CVV e validade não existem neste formulário nem no contrato.
 */
export interface NewCard {
  nickname: string
  institutionName: string | null
  brand: CardBrand
  last4: string
  limit: string
  closingDay: number
  dueDay: number
  defaultPaymentAccountId: string | null
}

export const createCard = (fields: NewCard) =>
  bffCall<Card>('/api/bff/cards', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(fields),
  })
