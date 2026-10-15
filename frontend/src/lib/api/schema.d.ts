/**
 * CONTRATO MANUAL E TEMPORÁRIO do backend usado pelo frontend (Identity, Overview, Accounts, Transactions, Imports,
 * Analytics e Health) — ADR-0007 §6.
 *
 * Escrito no formato do openapi-typescript a partir dos DTOs reais (OverviewResponse, ProvisionedUserResponse,
 * ApiError). É a fonte de verdade do frontend enquanto o OpenAPI do backend não declarar:
 *   - enums como enums (hoje os DTOs usam String → o gerador produziria `string`);
 *   - campos nulos (`totalBalance`, `cashFlow`, `balance`… → o gerador perderia a distinção null ≠ zero).
 *
 * Proteção contra drift: `contract.ts` + `contract.test.ts` comparam este arquivo com os records e enums Java.
 * `pnpm api:generate` gera `openapi.generated.d.ts` SEPARADO, só para comparação; ele não substitui este arquivo.
 */

export interface components {
  schemas: {
    MoneyDto: { amount: string; currency: string }
    ApiError: {
      code: string
      message: string
      details: { field?: string; code?: string; message?: string }[]
      traceId?: string
    }
    ProvisionedUserResponse: {
      id: string
      email: string
      displayName: string
      status: string
      createdAt: string
      workspaceId: string
    }
    OverviewResponse: {
      workspaceId: string
      period: { type: 'CURRENT_MONTH' | 'PREVIOUS_MONTH' | 'CUSTOM'; from: string; to: string }
      state: 'NO_ACCOUNTS' | 'NO_TRANSACTIONS' | 'NO_ACTIVITY_IN_PERIOD' | 'READY'
      summary: {
        totalBalance: components['schemas']['MoneyDto'] | null
        balanceAsOf: string
        accountCount: number
        pendingTransactions: number
      }
      cashFlow: {
        income: components['schemas']['MoneyDto'] | null
        expense: components['schemas']['MoneyDto'] | null
        refunds: components['schemas']['MoneyDto'] | null
        net: components['schemas']['MoneyDto'] | null
        transfers: { count: number; volume: components['schemas']['MoneyDto'] | null }
      } | null
      accounts: {
        id: string
        name: string
        type: 'CHECKING' | 'SAVINGS' | 'PAYMENT' | 'OTHER'
        institutionName: string | null
        includedInTotal: boolean
        balance: components['schemas']['MoneyDto'] | null
        movementCount: number
      }[]
      recentTransactions: {
        id: string
        type: 'INCOME' | 'EXPENSE' | 'TRANSFER' | 'ADJUSTMENT' | 'REFUND'
        flow: 'INFLOW' | 'OUTFLOW' | 'TRANSFER'
        adjustmentDirection: 'INCREASE' | 'DECREASE' | null
        status: 'PENDING' | 'POSTED' | 'CANCELLED' | 'REVERSED'
        amount: components['schemas']['MoneyDto']
        occurredOn: string
        description: string | null
        account: { id: string; name: string }
        destinationAccount: { id: string; name: string } | null
        refundOfTransactionId: string | null
        source: 'MANUAL' | 'IMPORT'
      }[]
    }
    AccountResponse: {
      id: string
      workspaceId: string
      name: string
      type: 'CHECKING' | 'SAVINGS' | 'PAYMENT' | 'OTHER'
      institutionName: string | null
      currency: string
      includedInTotal: boolean
      status: 'ACTIVE' | 'ARCHIVED'
      createdAt: string
      updatedAt: string
    }
    AccountListResponse: { items: components['schemas']['AccountResponse'][] }
    /** `includedInTotal` ausente assume true. Nome e instituição: até 100 caracteres (AccountName/InstitutionName). */
    CreateAccountRequest: {
      name: string
      type: 'CHECKING' | 'SAVINGS' | 'PAYMENT' | 'OTHER'
      institutionName?: string | null
      includedInTotal?: boolean
    }
    /** Substituição completa dos campos editáveis (ADR-0004 §8). */
    UpdateAccountRequest: {
      name: string
      type: 'CHECKING' | 'SAVINGS' | 'PAYMENT' | 'OTHER'
      institutionName: string | null
      includedInTotal: boolean
    }
    /** Transação (ADR-0005). Valor sempre positivo; o sentido vem de `type` (+ `adjustmentDirection`). */
    TransactionResponse: {
      id: string
      workspaceId: string
      accountId: string
      /** Só TRANSFER. */
      destinationAccountId: string | null
      type: 'INCOME' | 'EXPENSE' | 'TRANSFER' | 'ADJUSTMENT' | 'REFUND'
      /** Só ADJUSTMENT. */
      adjustmentDirection: 'INCREASE' | 'DECREASE' | null
      amount: components['schemas']['MoneyDto']
      occurredOn: string
      description: string
      status: 'PENDING' | 'POSTED' | 'CANCELLED' | 'REVERSED'
      source: 'MANUAL' | 'IMPORT'
      refundOfTransactionId: string | null
      /** Código do catálogo (`CategoryResponse.code`); `null` = "Sem categoria" (ADR-0012 §4). */
      categoryCode: string | null
      /** Como a categoria foi atribuída (ADR-0013 §3); `null` enquanto ninguém (regra ou usuário) a tratou. */
      categorySource: 'MANUAL' | 'RULE_LEARNED' | 'RULE_SYSTEM' | null
      /** Comerciante identificado na descrição (ADR-0013 §1); `null` se não identificado. */
      merchantName: string | null
      /** Em um REFUND criado por "marcar como reembolso": a receita importada estornada que ele substitui. */
      reclassifiedFromTransactionId: string | null
      /** Em uma receita estornada por "marcar como reembolso": o REFUND que a substituiu. */
      reclassifiedAsTransactionId: string | null
      createdAt: string
      updatedAt: string
    }
    TransactionPageResponse: {
      items: components['schemas']['TransactionResponse'][]
      page: number
      pageSize: number
      totalItems: number
    }
    /** Lançamento manual. `status` ausente assume POSTED; campos de outro tipo vão nulos/ausentes. */
    CreateTransactionRequest: {
      type: 'INCOME' | 'EXPENSE' | 'TRANSFER' | 'ADJUSTMENT' | 'REFUND'
      accountId: string
      destinationAccountId?: string | null
      adjustmentDirection?: 'INCREASE' | 'DECREASE' | null
      amount: components['schemas']['MoneyDto']
      occurredOn: string
      description: string
      status?: 'PENDING' | 'POSTED' | null
      refundOfTransactionId?: string | null
    }
    /** Só a descrição é editável (ADR-0005 §8). */
    UpdateTransactionRequest: { description: string }
    /**
     * `null` volta a "Sem categoria" (ADR-0012 §7). `applyToSimilar` aprende a regra do comerciante e a aplica às
     * transações semelhantes que o usuário ainda não escolheu (ADR-0013 §2).
     */
    CategorizeTransactionRequest: { categoryCode: string | null; applyToSimilar?: boolean }
    /** "Marcar como reembolso": vínculo opcional com a despesa original (EXPENSE lançada do Workspace). */
    MarkAsRefundRequest: { refundOfTransactionId?: string | null }
    /** Categoria do catálogo do sistema (ADR-0012). `EXPENSE` serve a despesa e reembolso; `INCOME`, a receita. */
    CategoryResponse: {
      code: string
      name: string
      nature: 'EXPENSE' | 'INCOME'
      active: boolean
    }
    CategoryListResponse: { items: components['schemas']['CategoryResponse'][] }
    /** Importação de extrato (ADR-0009). `lines.total = valid + invalid + duplicate`. */
    /** Métricas do período filtrado da lista (ADR-0016): só POSTED; net = income + refunds − expense. */
    TransactionSummaryResponse: {
      income: components['schemas']['MoneyDto']
      expense: components['schemas']['MoneyDto']
      refunds: components['schemas']['MoneyDto']
      net: components['schemas']['MoneyDto']
      transfers: { count: number; volume: components['schemas']['MoneyDto'] }
      postedCount: number
      pendingCount: number
    }
    /** Freshness da projeção de Analytics (ADR-0014 §6). */
    AnalyticsStatusResponse: {
      state: AnalysisFreshnessState
      refreshedAt: string | null
      pendingEvents: number
    }
    /** Freshness do snapshot do Health (ADR-0018 §7). */
    HealthStatusResponse: {
      state: HealthFreshnessState
      computedAt: string | null
      analyticsRefreshedAt: string | null
    }
    /** Health (ADR-0019): `state = NO_DATA` → score, confiança, cobertura e contexto nulos; listas vazias. */
    HealthResponse: {
      workspaceId: string
      state: HealthState
      algorithmVersion: string
      currency: string
      period: components['schemas']['HealthPeriodDto']
      score: number | null
      variation: number | null
      estimated: boolean
      confidence: 'HIGH' | 'MEDIUM' | 'LOW' | null
      coverage: components['schemas']['HealthCoverageDto'] | null
      summary: string | null
      context: components['schemas']['HealthContextDto'] | null
      freshness: components['schemas']['HealthFreshnessDto']
      dimensions: components['schemas']['HealthDimensionDto'][]
      factors: components['schemas']['HealthFactorDto'][]
      nextSteps: components['schemas']['HealthNextStepDto'][]
    }
    HealthPeriodDto: {
      month: string
      asOf: string | null
      partial: boolean
      windowFrom: string | null
      windowTo: string | null
      monthsUsed: number
    }
    HealthCoverageDto: { available: number; total: number; weightPercent: number }
    HealthContextDto: { activeAccounts: number; uncategorizedShare: string; sources: ('CSV' | 'MANUAL' | 'OFX')[] }
    HealthFreshnessDto: { state: HealthFreshnessState; computedAt: string | null; analyticsRefreshedAt: string | null }
    HealthDimensionDto: {
      dimension: HealthDimensionCode
      label: string
      weight: number
      status: 'OK' | 'INSUFFICIENT_DATA' | 'NOT_APPLICABLE'
      score: number | null
      reason: string | null
      explanation: string
      attention: boolean
      evidence: components['schemas']['HealthEvidenceDto'][]
    }
    HealthEvidenceDto: {
      code: string
      label: string
      kind: 'MONEY' | 'PERCENT' | 'MONTHS' | 'COUNT'
      value: string
      from: string
      to: string
      source: string
    }
    HealthFactorDto: { dimension: HealthDimensionCode; direction: 'SUSTAINING' | 'WEIGHING'; text: string }
    HealthNextStepDto: {
      kind: 'BUILD_RESERVE' | 'ADD_RESERVE_ACCOUNT' | 'REVIEW_CATEGORY' | 'CATEGORIZE_TRANSACTIONS' | 'REVIEW_MONTH'
      text: string
      evidence: string
      categoryCode: string | null
      month: string | null
      amount: components['schemas']['MoneyDto'] | null
    }
    HealthEvolutionResponse: {
      workspaceId: string
      algorithmVersion: string
      to: string
      freshness: components['schemas']['HealthFreshnessDto']
      months: components['schemas']['HealthMonthDto'][]
    }
    HealthMonthDto: {
      month: string
      state: HealthState
      score: number | null
      variation: number | null
      estimated: boolean
      partial: boolean
    }
    HealthMethodResponse: {
      algorithmVersion: string
      about: string
      dimensions: components['schemas']['HealthMethodDimensionDto'][]
      overall: string
      window: string
      sources: string[]
      estimated: string
      confidence: components['schemas']['HealthConfidenceRuleDto'][]
      partialMonth: string
      rounding: string
      exclusions: string[]
    }
    HealthMethodDimensionDto: {
      dimension: HealthDimensionCode
      label: string
      weight: number
      applicable: boolean
      observes: string
      formula: string
      minimum: string
      curves: components['schemas']['HealthCurveDto'][]
    }
    HealthCurveDto: { name: string; input: string; points: components['schemas']['HealthCurvePointDto'][] }
    HealthCurvePointDto: { x: string; score: string }
    HealthConfidenceRuleDto: { level: 'HIGH' | 'MEDIUM' | 'LOW'; rule: string }
    /** Análise (ADR-0015): `state = NO_DATA` → blocos de dados nulos; variações nulas = base indisponível. */
    AnalysisOverviewResponse: {
      workspaceId: string
      state: AnalysisDataState
      period: AnalysisPeriodInfo
      comparison: AnalysisComparison
      freshness: AnalysisFreshness
      income: AnalysisMetric | null
      expense: AnalysisMetric | null
      refunds: AnalysisMetric | null
      net: AnalysisMetric | null
      /** % da receita não gasta, 1 casa; nula sem receitas. */
      savedShare: string | null
      months: AnalysisMonth[] | null
      averageExpense: components['schemas']['MoneyDto'] | null
      composition: {
        income: components['schemas']['MoneyDto']
        refunds: components['schemas']['MoneyDto']
        inflows: components['schemas']['MoneyDto']
        outflows: {
          group: 'CATEGORY' | 'UNCATEGORIZED' | 'OTHERS'
          categoryCode: string | null
          amount: components['schemas']['MoneyDto']
          categories: number
        }[]
        net: components['schemas']['MoneyDto']
      } | null
      changes: {
        currentNet: components['schemas']['MoneyDto']
        baseNet: components['schemas']['MoneyDto']
        netDelta: components['schemas']['MoneyDto']
        items: {
          kind: 'INCOME' | 'REFUNDS' | 'EXPENSE_CATEGORY'
          categoryCode: string | null
          current: components['schemas']['MoneyDto']
          base: components['schemas']['MoneyDto']
          delta: components['schemas']['MoneyDto']
          impact: components['schemas']['MoneyDto']
          merchants: {
            merchantKey: string | null
            current: components['schemas']['MoneyDto']
            base: components['schemas']['MoneyDto']
            delta: components['schemas']['MoneyDto']
          }[]
        }[]
      } | null
      topCategories: AnalysisCategory[] | null
    }
    AnalysisSpendingResponse: {
      workspaceId: string
      state: AnalysisDataState
      period: AnalysisPeriodInfo
      comparison: AnalysisComparison
      freshness: AnalysisFreshness
      total: AnalysisMetric | null
      refunds: components['schemas']['MoneyDto'] | null
      dailyAverage: components['schemas']['MoneyDto'] | null
      previousDailyAverage: components['schemas']['MoneyDto'] | null
      cumulative: AnalysisDay[]
      previousCumulative: AnalysisDay[]
      largestExpenses: {
        transactionId: string
        occurredOn: string
        description: string
        merchantName: string | null
        categoryCode: string | null
        amount: components['schemas']['MoneyDto']
      }[]
      categories: AnalysisCategory[]
    }
    AnalysisIncomeResponse: {
      workspaceId: string
      state: AnalysisDataState
      period: AnalysisPeriodInfo
      comparison: AnalysisComparison
      freshness: AnalysisFreshness
      total: AnalysisMetric | null
      monthlyAverage: components['schemas']['MoneyDto'] | null
      sources: {
        merchantKey: string | null
        merchantName: string | null
        categoryCode: string | null
        amount: components['schemas']['MoneyDto']
        share: string | null
        transactionCount: number
        recurrence: 'RECURRING' | 'OCCASIONAL'
        typicalDay: number | null
        lastReceivedOn: string
      }[]
      months: AnalysisMonth[]
    }
    AnalysisCategoriesResponse: {
      workspaceId: string
      state: AnalysisDataState
      period: AnalysisPeriodInfo
      comparison: AnalysisComparison
      freshness: AnalysisFreshness
      totalExpense: components['schemas']['MoneyDto'] | null
      categories: AnalysisCategory[]
    }
    AnalysisCategoryDetailResponse: {
      workspaceId: string
      state: AnalysisDataState
      period: AnalysisPeriodInfo
      comparison: AnalysisComparison
      freshness: AnalysisFreshness
      categoryCode: string
      nature: 'EXPENSE' | 'INCOME'
      amount: AnalysisMetric | null
      transactionCount: number
      averageTicket: components['schemas']['MoneyDto'] | null
      history: { month: string; partial: boolean; amount: components['schemas']['MoneyDto']; transactionCount: number }[]
      merchants: {
        merchantKey: string | null
        merchantName: string | null
        amount: components['schemas']['MoneyDto']
        transactionCount: number
      }[]
    }
    AnalysisMerchantsResponse: {
      workspaceId: string
      state: AnalysisDataState
      period: AnalysisPeriodInfo
      comparison: AnalysisComparison
      freshness: AnalysisFreshness
      sort: 'AMOUNT' | 'COUNT'
      merchants: {
        merchantKey: string
        merchantName: string
        categoryCode: string | null
        transactionCount: number
        amount: components['schemas']['MoneyDto']
        vsPrevious: AnalysisVariation | null
        vsAverage: AnalysisVariation | null
        lastPurchaseOn: string | null
      }[]
      unidentified: components['schemas']['MoneyDto'] | null
    }
    AnalysisEvolutionResponse: {
      workspaceId: string
      state: AnalysisDataState
      period: AnalysisPeriodInfo
      comparison: AnalysisComparison
      freshness: AnalysisFreshness
      months: AnalysisMonth[]
    }
    ImportResponse: {
      id: string
      workspaceId: string
      accountId: string
      format: 'CSV' | 'OFX'
      status: 'PREVIEW' | 'CONFIRMED' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'EXPIRED'
      fileName: string
      fileSize: number
      lines: { total: number; valid: number; invalid: number; duplicate: number; imported: number; categorized: number }
      period: { from: string; to: string } | null
      sameFileImportedBefore: boolean
      failureReason: string | null
      createdAt: string
      previewExpiresAt: string | null
      confirmedAt: string | null
      completedAt: string | null
    }
    ImportPageResponse: {
      items: components['schemas']['ImportResponse'][]
      page: number
      pageSize: number
      totalItems: number
    }
    /** Linha do arquivo. Em INVALID só `lineNumber`, `status` e `issue` vêm preenchidos. */
    ImportRecordResponse: {
      lineNumber: number
      status: 'VALID' | 'INVALID' | 'DUPLICATE' | 'IMPORTED'
      occurredOn: string | null
      amount: components['schemas']['MoneyDto'] | null
      direction: 'INFLOW' | 'OUTFLOW' | null
      description: string | null
      issue: { field: string; code: string } | null
      transactionId: string | null
    }
    ImportRecordPageResponse: {
      items: components['schemas']['ImportRecordResponse'][]
      page: number
      pageSize: number
      totalItems: number
    }
  }
}

type MoneyDto = components['schemas']['MoneyDto']
export type AnalysisFreshnessState = 'UP_TO_DATE' | 'UPDATING' | 'NOT_BUILT'
export type HealthFreshnessState = 'UP_TO_DATE' | 'RECALCULATING' | 'NOT_BUILT'
export type HealthState = 'READY' | 'INSUFFICIENT_DATA' | 'NO_DATA'
export type HealthDimensionCode = 'CASH_FLOW' | 'SPENDING' | 'CREDIT' | 'DEBT' | 'RESERVE' | 'CONSISTENCY'
export type AnalysisTrend = 'UP' | 'DOWN' | 'STABLE' | 'NEW' | 'GONE'
/** Variação contra uma base (ADR-0015 §3): percent com 1 casa; nulo com base zero. */
export interface AnalysisVariation {
  base: MoneyDto
  difference: MoneyDto
  percent: string | null
  trend: AnalysisTrend
}
export interface AnalysisMetric {
  amount: MoneyDto
  vsPrevious: AnalysisVariation | null
  vsAverage: AnalysisVariation | null
}
export interface AnalysisMonth {
  month: string
  partial: boolean
  income: MoneyDto
  expense: MoneyDto
  refunds: MoneyDto
  net: MoneyDto
}
export interface AnalysisDay {
  day: number
  amount: MoneyDto
}
export interface AnalysisCategory {
  categoryCode: string | null
  amount: MoneyDto
  share: string | null
  transactionCount: number
  refunds: MoneyDto
  vsPrevious: AnalysisVariation | null
  vsAverage: AnalysisVariation | null
}
export type AnalysisDataState = 'NO_DATA' | 'NO_ACTIVITY_IN_PERIOD' | 'READY'
export interface AnalysisPeriodInfo {
  month: string
  from: string
  to: string
  partial: boolean
  daysElapsed: number
  daysInMonth: number
}
export interface AnalysisFreshness {
  state: AnalysisFreshnessState
  refreshedAt: string | null
  pendingEvents: number
}
export interface AnalysisComparison {
  selected: 'PREVIOUS' | 'AVERAGE'
  previous: { from: string; to: string } | null
  average: {
    requestedMonths: number
    monthsUsed: number
    months: string[]
    state: 'AVAILABLE' | 'LIMITED' | 'INSUFFICIENT_DATA'
  }
}
/** Campos comuns a toda resposta da Análise (ADR-0015 §4). */
export interface AnalysisMeta {
  workspaceId: string
  state: AnalysisDataState
  period: AnalysisPeriodInfo
  comparison: AnalysisComparison
  freshness: AnalysisFreshness
}
type AnalysisErrors = {
  400: { content: { 'application/json': components['schemas']['ApiError'] } }
  401: { content: { 'application/json': components['schemas']['ApiError'] } }
  404: { content: { 'application/json': components['schemas']['ApiError'] } }
}

type ImportErrors = {
  400: { content: { 'application/json': components['schemas']['ApiError'] } }
  404: { content: { 'application/json': components['schemas']['ApiError'] } }
  409: { content: { 'application/json': components['schemas']['ApiError'] } }
}

type ImportPath = { workspaceId: string; importId: string }

type AccountErrors = {
  400: { content: { 'application/json': components['schemas']['ApiError'] } }
  404: { content: { 'application/json': components['schemas']['ApiError'] } }
  409: { content: { 'application/json': components['schemas']['ApiError'] } }
}

type AccountPath = { workspaceId: string; accountId: string }
type AccountResult = {
  200: { content: { 'application/json': components['schemas']['AccountResponse'] } }
} & AccountErrors

type TransactionErrors = {
  400: { content: { 'application/json': components['schemas']['ApiError'] } }
  404: { content: { 'application/json': components['schemas']['ApiError'] } }
  409: { content: { 'application/json': components['schemas']['ApiError'] } }
}

type TransactionPath = { workspaceId: string; transactionId: string }
type TransactionResult = {
  200: { content: { 'application/json': components['schemas']['TransactionResponse'] } }
} & TransactionErrors

export interface paths {
  '/api/v1/users/me': {
    post: {
      responses: {
        200: { content: { 'application/json': components['schemas']['ProvisionedUserResponse'] } }
        201: { content: { 'application/json': components['schemas']['ProvisionedUserResponse'] } }
      }
    }
  }
  '/api/v1/workspaces/{workspaceId}/overview': {
    get: {
      parameters: {
        query?: {
          period?: 'CURRENT_MONTH' | 'PREVIOUS_MONTH' | 'CUSTOM'
          from?: string
          to?: string
          recentLimit?: number
        }
        path: { workspaceId: string }
      }
      responses: {
        200: { content: { 'application/json': components['schemas']['OverviewResponse'] } }
        400: { content: { 'application/json': components['schemas']['ApiError'] } }
        404: { content: { 'application/json': components['schemas']['ApiError'] } }
      }
    }
  }
  '/api/v1/workspaces/{workspaceId}/accounts': {
    get: {
      parameters: { query?: { includeArchived?: boolean }; path: { workspaceId: string } }
      responses: {
        200: { content: { 'application/json': components['schemas']['AccountListResponse'] } }
        404: { content: { 'application/json': components['schemas']['ApiError'] } }
      }
    }
    post: {
      parameters: { path: { workspaceId: string } }
      requestBody: { content: { 'application/json': components['schemas']['CreateAccountRequest'] } }
      responses: {
        201: { content: { 'application/json': components['schemas']['AccountResponse'] } }
      } & AccountErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/accounts/{accountId}': {
    get: { parameters: { path: AccountPath }; responses: AccountResult }
    put: {
      parameters: { path: AccountPath }
      requestBody: { content: { 'application/json': components['schemas']['UpdateAccountRequest'] } }
      responses: AccountResult
    }
  }
  '/api/v1/workspaces/{workspaceId}/accounts/{accountId}/archive': {
    post: { parameters: { path: AccountPath }; responses: AccountResult }
  }
  '/api/v1/workspaces/{workspaceId}/accounts/{accountId}/reactivate': {
    post: { parameters: { path: AccountPath }; responses: AccountResult }
  }
  '/api/v1/workspaces/{workspaceId}/transactions': {
    get: {
      parameters: {
        query?: {
          from?: string
          to?: string
          type?: components['schemas']['TransactionResponse']['type']
          status?: components['schemas']['TransactionResponse']['status']
          accountId?: string
          q?: string
          categoryCode?: string
          uncategorized?: boolean
          page?: number
          pageSize?: number
        }
        path: { workspaceId: string }
      }
      responses: {
        200: { content: { 'application/json': components['schemas']['TransactionPageResponse'] } }
      } & TransactionErrors
    }
    post: {
      parameters: { header: { 'Idempotency-Key': string }; path: { workspaceId: string } }
      requestBody: { content: { 'application/json': components['schemas']['CreateTransactionRequest'] } }
      responses: {
        200: { content: { 'application/json': components['schemas']['TransactionResponse'] } }
        201: { content: { 'application/json': components['schemas']['TransactionResponse'] } }
        422: { content: { 'application/json': components['schemas']['ApiError'] } }
      } & TransactionErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/transactions/summary': {
    get: {
      parameters: {
        query?: {
          from?: string
          to?: string
          type?: components['schemas']['TransactionResponse']['type']
          accountId?: string
          q?: string
          categoryCode?: string
          uncategorized?: boolean
        }
        path: { workspaceId: string }
      }
      responses: {
        200: { content: { 'application/json': components['schemas']['TransactionSummaryResponse'] } }
      } & TransactionErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/analytics/status': {
    get: {
      parameters: { path: { workspaceId: string } }
      responses: { 200: { content: { 'application/json': components['schemas']['AnalyticsStatusResponse'] } } } & AnalysisErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/health': {
    get: {
      parameters: { query?: { month?: string }; path: { workspaceId: string } }
      responses: { 200: { content: { 'application/json': components['schemas']['HealthResponse'] } } } & AnalysisErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/health/evolution': {
    get: {
      parameters: { query?: { month?: string; months?: number }; path: { workspaceId: string } }
      responses: { 200: { content: { 'application/json': components['schemas']['HealthEvolutionResponse'] } } } & AnalysisErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/health/method': {
    get: {
      parameters: { path: { workspaceId: string } }
      responses: { 200: { content: { 'application/json': components['schemas']['HealthMethodResponse'] } } } & AnalysisErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/health/status': {
    get: {
      parameters: { path: { workspaceId: string } }
      responses: { 200: { content: { 'application/json': components['schemas']['HealthStatusResponse'] } } } & AnalysisErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/analytics/overview': {
    get: {
      parameters: { query?: { month?: string; compareWith?: 'PREVIOUS' | 'AVERAGE'; averageMonths?: number }; path: { workspaceId: string } }
      responses: { 200: { content: { 'application/json': components['schemas']['AnalysisOverviewResponse'] } } } & AnalysisErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/analytics/spending': {
    get: {
      parameters: { query?: { month?: string; compareWith?: 'PREVIOUS' | 'AVERAGE'; averageMonths?: number }; path: { workspaceId: string } }
      responses: { 200: { content: { 'application/json': components['schemas']['AnalysisSpendingResponse'] } } } & AnalysisErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/analytics/income': {
    get: {
      parameters: { query?: { month?: string; compareWith?: 'PREVIOUS' | 'AVERAGE'; averageMonths?: number }; path: { workspaceId: string } }
      responses: { 200: { content: { 'application/json': components['schemas']['AnalysisIncomeResponse'] } } } & AnalysisErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/analytics/categories': {
    get: {
      parameters: { query?: { month?: string; compareWith?: 'PREVIOUS' | 'AVERAGE'; averageMonths?: number }; path: { workspaceId: string } }
      responses: { 200: { content: { 'application/json': components['schemas']['AnalysisCategoriesResponse'] } } } & AnalysisErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/analytics/categories/{categoryCode}': {
    get: {
      parameters: { query?: { month?: string; compareWith?: 'PREVIOUS' | 'AVERAGE'; averageMonths?: number }; path: { workspaceId: string; categoryCode: string } }
      responses: { 200: { content: { 'application/json': components['schemas']['AnalysisCategoryDetailResponse'] } } } & AnalysisErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/analytics/merchants': {
    get: {
      parameters: { query?: { month?: string; compareWith?: 'PREVIOUS' | 'AVERAGE'; averageMonths?: number; sort?: 'AMOUNT' | 'COUNT' }; path: { workspaceId: string } }
      responses: { 200: { content: { 'application/json': components['schemas']['AnalysisMerchantsResponse'] } } } & AnalysisErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/analytics/evolution': {
    get: {
      parameters: { query?: { month?: string; months?: number }; path: { workspaceId: string } }
      responses: { 200: { content: { 'application/json': components['schemas']['AnalysisEvolutionResponse'] } } } & AnalysisErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/transactions/{transactionId}': {
    get: { parameters: { path: TransactionPath }; responses: TransactionResult }
    put: {
      parameters: { path: TransactionPath }
      requestBody: { content: { 'application/json': components['schemas']['UpdateTransactionRequest'] } }
      responses: TransactionResult
    }
  }
  '/api/v1/workspaces/{workspaceId}/transactions/{transactionId}/category': {
    put: {
      parameters: { path: TransactionPath }
      requestBody: { content: { 'application/json': components['schemas']['CategorizeTransactionRequest'] } }
      responses: TransactionResult
    }
  }
  '/api/v1/workspaces/{workspaceId}/transactions/{transactionId}/post': {
    post: { parameters: { path: TransactionPath }; responses: TransactionResult }
  }
  '/api/v1/workspaces/{workspaceId}/transactions/{transactionId}/cancel': {
    post: { parameters: { path: TransactionPath }; responses: TransactionResult }
  }
  '/api/v1/workspaces/{workspaceId}/transactions/{transactionId}/reverse': {
    post: { parameters: { path: TransactionPath }; responses: TransactionResult }
  }
  /** Receita importada → estornada + REFUND igual (201 criado; 200 replay). Responde o reembolso. */
  '/api/v1/workspaces/{workspaceId}/transactions/{transactionId}/mark-as-refund': {
    post: {
      parameters: { path: TransactionPath }
      requestBody?: { content: { 'application/json': components['schemas']['MarkAsRefundRequest'] } }
      responses: {
        201: { content: { 'application/json': components['schemas']['TransactionResponse'] } }
      } & TransactionResult
    }
  }
  '/api/v1/workspaces/{workspaceId}/categories': {
    get: {
      parameters: { path: { workspaceId: string } }
      responses: {
        200: { content: { 'application/json': components['schemas']['CategoryListResponse'] } }
        404: { content: { 'application/json': components['schemas']['ApiError'] } }
      }
    }
  }
  '/api/v1/workspaces/{workspaceId}/imports': {
    get: {
      parameters: { query?: { page?: number; pageSize?: number }; path: { workspaceId: string } }
      responses: {
        200: { content: { 'application/json': components['schemas']['ImportPageResponse'] } }
      } & ImportErrors
    }
    post: {
      parameters: {
        query: { accountId: string }
        header: { 'Idempotency-Key': string }
        path: { workspaceId: string }
      }
      /** O BFF envia `FormData` com a parte `file`. */
      requestBody: { content: { 'multipart/form-data': { file: Blob } } }
      responses: {
        200: { content: { 'application/json': components['schemas']['ImportResponse'] } }
        201: { content: { 'application/json': components['schemas']['ImportResponse'] } }
        413: { content: { 'application/json': components['schemas']['ApiError'] } }
        422: { content: { 'application/json': components['schemas']['ApiError'] } }
      } & ImportErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/imports/{importId}': {
    get: {
      parameters: { path: ImportPath }
      responses: {
        200: { content: { 'application/json': components['schemas']['ImportResponse'] } }
      } & ImportErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/imports/{importId}/records': {
    get: {
      parameters: {
        query?: { status?: 'VALID' | 'INVALID' | 'DUPLICATE' | 'IMPORTED'; page?: number; pageSize?: number }
        path: ImportPath
      }
      responses: {
        200: { content: { 'application/json': components['schemas']['ImportRecordPageResponse'] } }
      } & ImportErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/imports/{importId}/confirm': {
    post: {
      parameters: { path: ImportPath }
      responses: {
        202: { content: { 'application/json': components['schemas']['ImportResponse'] } }
      } & ImportErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/imports/{importId}/cancel': {
    post: {
      parameters: { path: ImportPath }
      responses: {
        200: { content: { 'application/json': components['schemas']['ImportResponse'] } }
      } & ImportErrors
    }
  }
}
