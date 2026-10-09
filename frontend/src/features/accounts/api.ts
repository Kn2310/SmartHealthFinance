import type { components } from '@/lib/api/schema'
import type { Overview } from '@/lib/api/types'
import { bffCall } from '@/lib/bff-client'

type Schemas = components['schemas']
export type Account = Schemas['AccountResponse']
export type AccountType = Account['type']
/** Os 4 campos editáveis (criar e editar usam o mesmo formulário). */
export type AccountFields = Schemas['UpdateAccountRequest']

const JSON_BODY = { 'Content-Type': 'application/json' }
const path = (id: string) => `/api/bff/accounts/${encodeURIComponent(id)}`

/** Todas as contas, inclusive arquivadas: o filtro da tela é só de exibição. */
export const fetchAllAccounts = () =>
  bffCall<Schemas['AccountListResponse']>('/api/bff/accounts?includeArchived=true')

export const fetchAccount = (id: string) => bffCall<Account>(path(id))

/** Saldo inicial: valor positivo como string decimal ("1500.00") + direção, como num ajuste (ADR-0004 §4). */
export interface OpeningBalance {
  amount: string
  direction: 'INCREASE' | 'DECREASE'
}

/** `openingBalance` só vem quando foi pedido: RECORDED, ou FAILED (conta criada, ajuste não lançado). */
export type CreatedAccount = Account & { openingBalance?: 'RECORDED' | 'FAILED' }

export const createAccount = (fields: AccountFields, openingBalance?: OpeningBalance) =>
  bffCall<CreatedAccount>('/api/bff/accounts', {
    method: 'POST',
    headers: JSON_BODY,
    body: JSON.stringify(openingBalance ? { ...fields, openingBalance } : fields),
  })

/** "Tentar lançar saldo inicial novamente": o BFF usa a mesma chave da criação, então nunca duplica. */
export const retryOpeningBalance = (id: string, openingBalance: OpeningBalance) =>
  bffCall<{ openingBalance: 'RECORDED' }>(`${path(id)}/opening-balance`, {
    method: 'POST',
    headers: JSON_BODY,
    body: JSON.stringify(openingBalance),
  })

export const updateAccount = (id: string, fields: AccountFields) =>
  bffCall<Account>(path(id), { method: 'PUT', headers: JSON_BODY, body: JSON.stringify(fields) })

export const archiveAccount = (id: string) => bffCall<Account>(`${path(id)}/archive`, { method: 'POST' })

export const reactivateAccount = (id: string) => bffCall<Account>(`${path(id)}/reactivate`, { method: 'POST' })

/** Saldos vêm do Overview (ADR-0006): o frontend nunca soma movimentações. */
export const fetchOverview = () => bffCall<Overview>('/api/bff/overview')
