import type { components } from '@/lib/api/schema'
import { bffCall } from '@/lib/bff-client'

type Schemas = components['schemas']
export type Card = Schemas['CardResponse']
export type CardBrand = Card['brand']
export type CardsSummary = Schemas['CardsSummaryResponse']
export type CardSummary = Schemas['CardSummaryResponse']
export type InvoiceSummary = Schemas['InvoiceSummaryResponse']
export type Invoice = Schemas['InvoiceResponse']
export type InvoiceList = Schemas['InvoiceListResponse']
export type InvoiceItem = Schemas['InvoiceItemResponse']
export type InvoiceItemPage = Schemas['InvoiceItemPageResponse']
export type FutureInvoices = Schemas['FutureInvoicesResponse']
export type Utilization = Schemas['UtilizationResponse']
export type UtilizationHistory = Schemas['UtilizationHistoryResponse']
export type InstallmentPlan = Schemas['InstallmentPlanResponse']
export type InstallmentPlanList = Schemas['InstallmentPlanListResponse']

/**
 * Campos do cartão como o BFF espera (ADR-0026 §2), no cadastro e na edição: limite como string decimal, dias como
 * inteiros e só os 4 últimos dígitos. Número completo, CVV e validade não existem neste formulário nem no contrato.
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

const JSON_BODY = { 'Content-Type': 'application/json' }

/** Caminho do BFF de um cartão; ids e meses vão sempre codificados. */
export const cardPath = (id: string, ...rest: string[]) =>
  ['/api/bff/cards', id, ...rest].map((part, i) => (i === 0 ? part : encodeURIComponent(part))).join('/')

export const createCard = (fields: NewCard) =>
  bffCall<Card>('/api/bff/cards', { method: 'POST', headers: JSON_BODY, body: JSON.stringify(fields) })

export const updateCard = (id: string, fields: NewCard) =>
  bffCall<Card>(cardPath(id), { method: 'PUT', headers: JSON_BODY, body: JSON.stringify(fields) })

export const archiveCard = (id: string) => bffCall<Card>(cardPath(id, 'archive'), { method: 'POST' })

export const reactivateCard = (id: string) => bffCall<Card>(cardPath(id, 'reactivate'), { method: 'POST' })
