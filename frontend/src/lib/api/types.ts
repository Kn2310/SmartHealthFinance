import type { components } from './schema'

type Schemas = components['schemas']

export type MoneyDto = Schemas['MoneyDto']
export type Overview = Schemas['OverviewResponse']
export type OverviewState = Overview['state']
export type PeriodType = Overview['period']['type']
export type OverviewAccount = Overview['accounts'][number]
export type OverviewTransaction = Overview['recentTransactions'][number]
export type CashFlow = NonNullable<Overview['cashFlow']>

// Análise (ADR-0015) e resumo do período da lista (ADR-0016)
export type {
  AnalysisCategory,
  AnalysisDay,
  AnalysisMeta,
  AnalysisMetric,
  AnalysisMonth,
  AnalysisTrend,
  AnalysisVariation,
} from './schema'
export type AnalysisOverview = Schemas['AnalysisOverviewResponse']
export type AnalysisSpending = Schemas['AnalysisSpendingResponse']
export type AnalysisIncome = Schemas['AnalysisIncomeResponse']
export type AnalysisCategories = Schemas['AnalysisCategoriesResponse']
export type AnalysisCategoryDetail = Schemas['AnalysisCategoryDetailResponse']
export type AnalysisMerchants = Schemas['AnalysisMerchantsResponse']
export type AnalysisEvolution = Schemas['AnalysisEvolutionResponse']
export type AnalyticsStatus = Schemas['AnalyticsStatusResponse']
export type TransactionSummary = Schemas['TransactionSummaryResponse']
