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
export const TRANSACTION_SOURCES = ['MANUAL', 'IMPORT'] as const
export const ACCOUNT_STATUSES = ['ACTIVE', 'ARCHIVED'] as const
export const IMPORT_FORMATS = ['CSV', 'OFX'] as const
export const IMPORT_STATUSES = ['PREVIEW', 'CONFIRMED', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED'] as const
export const RECORD_STATUSES = ['VALID', 'INVALID', 'DUPLICATE', 'IMPORTED'] as const
/** Direção de uma linha importada (ImportRecordResponse): só entrada ou saída, nunca transferência. */
export const RECORD_DIRECTIONS = ['INFLOW', 'OUTFLOW'] as const

/** `AccountName.MAX_LENGTH` e `InstitutionName.MAX_LENGTH` do backend: o BFF e o formulário recusam antes. */
export const ACCOUNT_NAME_MAX_LENGTH = 100
export const INSTITUTION_NAME_MAX_LENGTH = 100

type Tx = O['recentTransactions'][number]
type Acc = S['AccountResponse']
type CreateAcc = S['CreateAccountRequest']
type UpdateAcc = S['UpdateAccountRequest']
type Imp = S['ImportResponse']
type Rec = S['ImportRecordResponse']
export type EnumChecks = [
  Assert<Equals<Values<typeof OVERVIEW_STATES>, O['state']>>,
  Assert<Equals<Values<typeof PERIOD_TYPES>, O['period']['type']>>,
  Assert<Equals<Values<typeof ACCOUNT_TYPES>, O['accounts'][number]['type']>>,
  Assert<Equals<Values<typeof TRANSACTION_TYPES>, Tx['type']>>,
  Assert<Equals<Values<typeof MOVEMENT_FLOWS>, Tx['flow']>>,
  Assert<Equals<Values<typeof TRANSACTION_STATUSES>, Tx['status']>>,
  Assert<Equals<Values<typeof ADJUSTMENT_DIRECTIONS>, NonNullable<Tx['adjustmentDirection']>>>,
  Assert<Equals<Values<typeof TRANSACTION_SOURCES>, Tx['source']>>,
  Assert<Equals<Values<typeof ACCOUNT_TYPES>, Acc['type']>>,
  Assert<Equals<Values<typeof ACCOUNT_STATUSES>, Acc['status']>>,
  Assert<Equals<Values<typeof ACCOUNT_TYPES>, CreateAcc['type']>>,
  Assert<Equals<Values<typeof ACCOUNT_TYPES>, UpdateAcc['type']>>,
  Assert<Equals<Values<typeof IMPORT_FORMATS>, Imp['format']>>,
  Assert<Equals<Values<typeof IMPORT_STATUSES>, Imp['status']>>,
  Assert<Equals<Values<typeof RECORD_STATUSES>, Rec['status']>>,
  Assert<Equals<Values<typeof RECORD_DIRECTIONS>, NonNullable<Rec['direction']>>>,
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
    'destinationAccount', 'refundOfTransactionId', 'source',
  ],
  MoneyDto: ['amount', 'currency'],
  AccountResponse: [
    'id', 'workspaceId', 'name', 'type', 'institutionName', 'currency', 'includedInTotal', 'status', 'createdAt',
    'updatedAt',
  ],
  AccountListResponse: ['items'],
  CreateAccountRequest: ['name', 'type', 'institutionName', 'includedInTotal'],
  UpdateAccountRequest: ['name', 'type', 'institutionName', 'includedInTotal'],
  ImportResponse: [
    'id', 'workspaceId', 'accountId', 'format', 'status', 'fileName', 'fileSize', 'lines', 'period',
    'sameFileImportedBefore', 'failureReason', 'createdAt', 'previewExpiresAt', 'confirmedAt', 'completedAt',
  ],
  Lines: ['total', 'valid', 'invalid', 'duplicate', 'imported'],
  ImportPeriod: ['from', 'to'],
  ImportRecordResponse: [
    'lineNumber', 'status', 'occurredOn', 'amount', 'direction', 'description', 'issue', 'transactionId',
  ],
  Issue: ['field', 'code'],
  ImportPageResponse: ['items', 'page', 'pageSize', 'totalItems'],
  ImportRecordPageResponse: ['items', 'page', 'pageSize', 'totalItems'],
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
  Assert<Equals<Values<F['AccountResponse']>, keyof Acc>>,
  Assert<Equals<Values<F['AccountListResponse']>, keyof S['AccountListResponse']>>,
  Assert<Equals<Values<F['CreateAccountRequest']>, keyof CreateAcc>>,
  Assert<Equals<Values<F['UpdateAccountRequest']>, keyof UpdateAcc>>,
  Assert<Equals<Values<F['ImportResponse']>, keyof Imp>>,
  Assert<Equals<Values<F['Lines']>, keyof Imp['lines']>>,
  Assert<Equals<Values<F['ImportPeriod']>, keyof NonNullable<Imp['period']>>>,
  Assert<Equals<Values<F['ImportRecordResponse']>, keyof Rec>>,
  Assert<Equals<Values<F['Issue']>, keyof NonNullable<Rec['issue']>>>,
  Assert<Equals<Values<F['ImportPageResponse']>, keyof S['ImportPageResponse']>>,
  Assert<Equals<Values<F['ImportRecordPageResponse']>, keyof S['ImportRecordPageResponse']>>,
]
