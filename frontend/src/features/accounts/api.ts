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

export const createAccount = (fields: AccountFields) =>
  bffCall<Account>('/api/bff/accounts', { method: 'POST', headers: JSON_BODY, body: JSON.stringify(fields) })

export const updateAccount = (id: string, fields: AccountFields) =>
  bffCall<Account>(path(id), { method: 'PUT', headers: JSON_BODY, body: JSON.stringify(fields) })

export const archiveAccount = (id: string) => bffCall<Account>(`${path(id)}/archive`, { method: 'POST' })

export const reactivateAccount = (id: string) => bffCall<Account>(`${path(id)}/reactivate`, { method: 'POST' })

/** Saldos vêm do Overview (ADR-0006): o frontend nunca soma movimentações. */
export const fetchOverview = () => bffCall<Overview>('/api/bff/overview')
