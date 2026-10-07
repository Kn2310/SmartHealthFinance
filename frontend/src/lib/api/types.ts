import type { components } from './schema'

type Schemas = components['schemas']

export type MoneyDto = Schemas['MoneyDto']
export type Overview = Schemas['OverviewResponse']
export type OverviewState = Overview['state']
export type PeriodType = Overview['period']['type']
export type OverviewAccount = Overview['accounts'][number]
export type OverviewTransaction = Overview['recentTransactions'][number]
export type CashFlow = NonNullable<Overview['cashFlow']>
