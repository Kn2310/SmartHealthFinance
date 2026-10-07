import type { components } from './schema'

/**
 * Espelho em tempo de execução do contrato manual (`schema.d.ts`), usado pelo teste de drift
 * (`contract.test.ts`), que o compara com os DTOs e enums Java do backend.
 *
 * As checagens de tipo abaixo garantem que estas listas e o `schema.d.ts` não divergem entre si: um valor ou
 * campo a mais/a menos em qualquer um dos dois quebra o `typecheck`.
 */

type S = components['schemas']
type O = S['OverviewResponse']

type Equals<A, B> = (<T>() => T extends A ? 1 : 2) extends <T>() => T extends B ? 1 : 2 ? true : false
type Assert<T extends true> = T
type Values<T extends readonly unknown[]> = T[number]

export const OVERVIEW_STATES = ['NO_ACCOUNTS', 'NO_TRANSACTIONS', 'NO_ACTIVITY_IN_PERIOD', 'READY'] as const
export const PERIOD_TYPES = ['CURRENT_MONTH', 'PREVIOUS_MONTH', 'CUSTOM'] as const
export const ACCOUNT_TYPES = ['CHECKING', 'SAVINGS', 'PAYMENT', 'OTHER'] as const
export const TRANSACTION_TYPES = ['INCOME', 'EXPENSE', 'TRANSFER', 'ADJUSTMENT', 'REFUND'] as const
export const MOVEMENT_FLOWS = ['INFLOW', 'OUTFLOW', 'TRANSFER'] as const
export const TRANSACTION_STATUSES = ['PENDING', 'POSTED', 'CANCELLED', 'REVERSED'] as const
export const ADJUSTMENT_DIRECTIONS = ['INCREASE', 'DECREASE'] as const

type Tx = O['recentTransactions'][number]
export type EnumChecks = [
  Assert<Equals<Values<typeof OVERVIEW_STATES>, O['state']>>,
  Assert<Equals<Values<typeof PERIOD_TYPES>, O['period']['type']>>,
  Assert<Equals<Values<typeof ACCOUNT_TYPES>, O['accounts'][number]['type']>>,
  Assert<Equals<Values<typeof TRANSACTION_TYPES>, Tx['type']>>,
  Assert<Equals<Values<typeof MOVEMENT_FLOWS>, Tx['flow']>>,
  Assert<Equals<Values<typeof TRANSACTION_STATUSES>, Tx['status']>>,
  Assert<Equals<Values<typeof ADJUSTMENT_DIRECTIONS>, NonNullable<Tx['adjustmentDirection']>>>,
]

/** Campos de cada record Java (nomes iguais aos do backend) ↔ chaves do tipo TS correspondente. */
export const RECORD_FIELDS = {
  OverviewResponse: ['workspaceId', 'period', 'state', 'summary', 'cashFlow', 'accounts', 'recentTransactions'],
  Period: ['type', 'from', 'to'],
  Summary: ['totalBalance', 'balanceAsOf', 'accountCount', 'pendingTransactions'],
  CashFlow: ['income', 'expense', 'refunds', 'net', 'transfers'],
  Transfers: ['count', 'volume'],
  AccountSummary: ['id', 'name', 'type', 'institutionName', 'includedInTotal', 'balance', 'movementCount'],
  AccountRef: ['id', 'name'],
  RecentTransaction: [
    'id', 'type', 'flow', 'adjustmentDirection', 'status', 'amount', 'occurredOn', 'description', 'account',
    'destinationAccount', 'refundOfTransactionId',
  ],
  MoneyDto: ['amount', 'currency'],
} as const

type F = typeof RECORD_FIELDS
export type FieldChecks = [
  Assert<Equals<Values<F['OverviewResponse']>, keyof O>>,
  Assert<Equals<Values<F['Period']>, keyof O['period']>>,
  Assert<Equals<Values<F['Summary']>, keyof O['summary']>>,
  Assert<Equals<Values<F['CashFlow']>, keyof NonNullable<O['cashFlow']>>>,
  Assert<Equals<Values<F['Transfers']>, keyof NonNullable<O['cashFlow']>['transfers']>>,
  Assert<Equals<Values<F['AccountSummary']>, keyof O['accounts'][number]>>,
  Assert<Equals<Values<F['AccountRef']>, keyof Tx['account']>>,
  Assert<Equals<Values<F['RecentTransaction']>, keyof Tx>>,
  Assert<Equals<Values<F['MoneyDto']>, keyof S['MoneyDto']>>,
]
