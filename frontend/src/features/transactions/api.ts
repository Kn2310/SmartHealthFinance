import type { components } from '@/lib/api/schema'
import { bffCall } from '@/lib/bff-client'

type Schemas = components['schemas']
export type Transaction = Schemas['TransactionResponse']
export type TransactionPage = Schemas['TransactionPageResponse']
export type TransactionType = Transaction['type']
export type TransactionStatus = Transaction['status']
export type AdjustmentDirection = NonNullable<Transaction['adjustmentDirection']>
export type Account = Schemas['AccountResponse']
export type Card = Schemas['CardResponse']
export type Category = Schemas['CategoryResponse']

/** Tipos que a UI lança em `/transactions` (transferência e reembolso fecham a pendência do M2 — ADR-0026 §10). */
export type ManualType = 'INCOME' | 'EXPENSE' | 'ADJUSTMENT' | 'TRANSFER' | 'REFUND'

/** Lançamento manual como o BFF espera: valor como string decimal ("1234.56"), nunca `number`. */
export interface NewTransaction {
  type: ManualType
  accountId: string
  /** Só TRANSFER. */
  destinationAccountId: string | null
  adjustmentDirection: AdjustmentDirection | null
  amount: string
  occurredOn: string
  description: string
  status: 'POSTED' | 'PENDING'
  /** Só REFUND, opcional: a despesa original. */
  refundOfTransactionId: string | null
}

/** Compra no cartão: o total; as parcelas são calculadas no backend (centavos na 1ª — ADR-0025 §6). */
export interface NewCardPurchase {
  amount: string
  occurredOn: string
  description: string
  installmentCount: number
}

/** Pagamento de fatura: transferência da conta para o cartão (ADR-0025 §7). */
export interface NewCardPayment {
  accountId: string
  amount: string
  occurredOn: string
  description: string | null
}

const cardPath = (cardId: string, operation: 'purchases' | 'payments') =>
  `/api/bff/cards/${encodeURIComponent(cardId)}/${operation}`

/** A mesma `idempotencyKey` em todo reenvio da mesma intenção (ADR-0026 §10). */
export const recordCardPurchase = (cardId: string, fields: NewCardPurchase, idempotencyKey: string) =>
  bffCall<Schemas['CardPurchaseResponse']>(cardPath(cardId, 'purchases'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(fields),
  })

export const recordCardPayment = (cardId: string, fields: NewCardPayment, idempotencyKey: string) =>
  bffCall<Schemas['CardTransactionResponse']>(cardPath(cardId, 'payments'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(fields),
  })

/** Filtros enviados ao BFF (todos opcionais; o Workspace vem sempre da sessão). */
export interface ListParams {
  from?: string
  to?: string
  type?: TransactionType
  status?: TransactionStatus
  accountId?: string
  q?: string
  /** Uma categoria do catálogo (ADR-0012). */
  categoryCode?: string
  /** Só "Sem categoria". */
  uncategorized?: 'true'
}

const JSON_BODY = { 'Content-Type': 'application/json' }
const path = (id: string) => `/api/bff/transactions/${encodeURIComponent(id)}`

export function listSearch(params: ListParams, page = 0): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) if (value) search.set(key, value)
  if (page > 0) search.set('page', String(page))
  return search.toString()
}

export function fetchTransactions(params: ListParams, page = 0) {
  const search = listSearch(params, page)
  return bffCall<TransactionPage>(`/api/bff/transactions${search ? `?${search}` : ''}`)
}

export const fetchTransaction = (id: string) => bffCall<Transaction>(path(id))

/** A mesma `idempotencyKey` em todo reenvio da mesma intenção: duplo clique ou retry nunca duplicam. */
export const createTransaction = (fields: NewTransaction, idempotencyKey: string) =>
  bffCall<Transaction>('/api/bff/transactions', {
    method: 'POST',
    headers: { ...JSON_BODY, 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(fields),
  })

export const updateDescription = (id: string, description: string) =>
  bffCall<Transaction>(path(id), { method: 'PUT', headers: JSON_BODY, body: JSON.stringify({ description }) })

/** Atribui uma categoria do catálogo ou volta a "Sem categoria" (`null`) — ADR-0012 §7. */
/** `applyToSimilar`: aprende a regra do comerciante e a aplica às transações semelhantes (ADR-0013 §2). */
export const categorizeTransaction = (id: string, categoryCode: string | null, applyToSimilar = false) =>
  bffCall<Transaction>(`${path(id)}/category`, {
    method: 'PUT',
    headers: JSON_BODY,
    body: JSON.stringify(applyToSimilar ? { categoryCode, applyToSimilar } : { categoryCode }),
  })

export type TransitionAction = 'post' | 'cancel' | 'reverse'

export const transitionTransaction = (id: string, action: TransitionAction) =>
  bffCall<Transaction>(`${path(id)}/${action}`, { method: 'POST' })

/** Todas as contas (inclusive arquivadas): os nomes aparecem no histórico mesmo depois de arquivar. */
export const fetchAllAccounts = () =>
  bffCall<Schemas['AccountListResponse']>('/api/bff/accounts?includeArchived=true')

/** Todos os cartões (inclusive arquivados): os rótulos aparecem no histórico mesmo depois de arquivar. */
export const fetchAllCards = () => bffCall<Schemas['CardListResponse']>('/api/bff/cards?includeArchived=true')

/** Catálogo de categorias (inclusive inativas, para os nomes de transações antigas), na ordem de exibição. */
export const fetchCategories = () => bffCall<Schemas['CategoryListResponse']>('/api/bff/categories')

/** Chave nova por intenção de lançamento (formato aceito pelo backend: não reservados de URI + ':'). */
export const newIdempotencyKey = () => `web:${crypto.randomUUID()}`

/**
 * "Marcar como reembolso": a receita importada é estornada e substituída por um REFUND igual. Responde o reembolso
 * (201 na primeira vez, 200 no replay). `refundOfTransactionId` opcional: a despesa original.
 */
export const markAsRefund = (id: string, refundOfTransactionId: string | null) =>
  bffCall<Transaction>(`${path(id)}/mark-as-refund`, {
    method: 'POST',
    headers: JSON_BODY,
    body: JSON.stringify(refundOfTransactionId ? { refundOfTransactionId } : {}),
  })
