import type { components } from '@/lib/api/schema'
import { bffCall } from '@/lib/bff-client'

type Schemas = components['schemas']
export type Transaction = Schemas['TransactionResponse']
export type TransactionPage = Schemas['TransactionPageResponse']
export type TransactionType = Transaction['type']
export type TransactionStatus = Transaction['status']
export type AdjustmentDirection = NonNullable<Transaction['adjustmentDirection']>
export type Account = Schemas['AccountResponse']

/** Tipos que a UI lança (transferência e reembolso pela UI ficam fora do escopo; se existirem, só são exibidos). */
export type ManualType = 'INCOME' | 'EXPENSE' | 'ADJUSTMENT'

/** Lançamento manual como o BFF espera: valor como string decimal ("1234.56"), nunca `number`. */
export interface NewTransaction {
  type: ManualType
  accountId: string
  adjustmentDirection: AdjustmentDirection | null
  amount: string
  occurredOn: string
  description: string
  status: 'POSTED' | 'PENDING'
}

/** Filtros enviados ao BFF (todos opcionais; o Workspace vem sempre da sessão). */
export interface ListParams {
  from?: string
  to?: string
  type?: TransactionType
  status?: TransactionStatus
  accountId?: string
  q?: string
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

export type TransitionAction = 'post' | 'cancel' | 'reverse'

export const transitionTransaction = (id: string, action: TransitionAction) =>
  bffCall<Transaction>(`${path(id)}/${action}`, { method: 'POST' })

/** Todas as contas (inclusive arquivadas): os nomes aparecem no histórico mesmo depois de arquivar. */
export const fetchAllAccounts = () =>
  bffCall<Schemas['AccountListResponse']>('/api/bff/accounts?includeArchived=true')

/** Chave nova por intenção de lançamento (formato aceito pelo backend: não reservados de URI + ':'). */
export const newIdempotencyKey = () => `web:${crypto.randomUUID()}`
