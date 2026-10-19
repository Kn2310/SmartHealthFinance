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

// Financial Health (ADR-0019)
export type { HealthDimensionCode, HealthFreshnessState, HealthState } from './schema'
export type Health = Schemas['HealthResponse']
export type HealthDimension = Schemas['HealthDimensionDto']
export type HealthEvidence = Schemas['HealthEvidenceDto']
export type HealthFactor = Schemas['HealthFactorDto']
export type HealthNextStep = Schemas['HealthNextStepDto']
export type HealthFreshness = Schemas['HealthFreshnessDto']
export type HealthEvolution = Schemas['HealthEvolutionResponse']
export type HealthMonth = Schemas['HealthMonthDto']
export type HealthMethod = Schemas['HealthMethodResponse']
export type HealthStatus = Schemas['HealthStatusResponse']

// Insights (ADR-0021/0022/0023)
export type {
  InsightActionKind,
  InsightConfidence,
  InsightDataState,
  InsightDetector,
  InsightFeedback,
  InsightFigureKind,
  InsightImportance,
  InsightType,
  InsightsFreshnessState,
} from './schema'
export type InsightList = Schemas['InsightListResponse']
export type InsightItem = Schemas['InsightItemDto']
export type InsightAction = Schemas['InsightActionDto']
export type InsightCounts = Schemas['InsightCountsDto']
export type InsightDetail = Schemas['InsightDetailResponse']
export type InsightFigure = Schemas['InsightFigureDto']
export type InsightHighlights = Schemas['InsightHighlightsResponse']
export type InsightChange = Schemas['InsightChangeDto']
export type InsightRelated = Schemas['InsightRelatedResponse']
export type InsightsFreshness = Schemas['InsightsFreshnessDto']
