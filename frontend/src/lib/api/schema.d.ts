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
        /** Nula em compra e estorno no cartão (ADR-0026 §10). */
        account: { id: string; name: string } | null
        destinationAccount: { id: string; name: string } | null
        refundOfTransactionId: string | null
        source: 'MANUAL' | 'IMPORT'
        /** Cartão da compra/estorno ou que recebe o pagamento de fatura; só os 4 últimos dígitos. */
        card: { id: string; nickname: string; last4: string } | null
      }[]
      /** "Faturas a pagar" e "Próximos eventos", sempre em hoje; nulo sem cartão ativo (ADR-0006 §18). */
      cards: components['schemas']['InvoicesToPay'] | null
    }
    /** Dívida dos cartões, separada do saldo total e sem "saldo líquido" (ADR-0006 §18). Só fatos. */
    InvoicesToPay: {
      activeCards: number
      /** O que falta pagar nas faturas já iniciadas, inclusive a atual (parcial). */
      openInvoices: MoneyDto
      /** Vencimento mais próximo com saldo em aberto (pode já ter passado); nulo sem saldo. */
      nextDue: components['schemas']['InvoicesNextDue'] | null
      upcomingEvents: components['schemas']['UpcomingInvoiceEvent'][]
      asOf: string
      calculationVersion: string
    }
    InvoicesNextDue: { date: string; amount: MoneyDto }
    UpcomingInvoiceEvent: {
      date: string
      kind: UpcomingInvoiceEventKind
      /** PARTIAL = fatura aberta (valor até agora); CLOSED = saldo em aberto; OVERDUE = vencida com saldo. */
      status: UpcomingInvoiceEventStatus
      dueMonth: string
      amount: MoneyDto
      card: { id: string; nickname: string; last4: string }
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
      /** Nula em compra e estorno no cartão, que trazem `cardId` (ADR-0026 §10). */
      accountId: string | null
      /** Só TRANSFER entre contas. */
      destinationAccountId: string | null
      /** Cartão da compra ou do estorno. */
      cardId: string | null
      /** Cartão que recebe o pagamento de fatura (TRANSFER conta → cartão). */
      destinationCardId: string | null
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
    /** Freshness dos insights (ADR-0022 §8). */
    InsightsStatusResponse: {
      state: InsightsFreshnessState
      generatedAt: string | null
      healthComputedAt: string | null
    }
    InsightsFreshnessDto: { state: InsightsFreshnessState; generatedAt: string | null; healthComputedAt: string | null }
    /** `month` nulo nos insights sem período (reserva). */
    InsightPeriodDto: { month: string | null; partial: boolean }
    /** `available = false` nos placeholders "Em breve" (objetivo, Copilot); a rota é montada pela UI (ADR-0021 §2). */
    InsightActionDto: {
      kind: InsightActionKind
      label: string
      primary: boolean
      available: boolean
      params: Record<string, string>
    }
    /** Insight na lista, nos destaques e nos relacionados (ADR-0023). "Novo" = ativo e `seen = false`. */
    InsightItemDto: {
      id: string
      detector: InsightDetector
      type: InsightType
      typeLabel: string
      importance: InsightImportance
      status: InsightStatus
      title: string
      support: string
      subjectLabel: string
      period: components['schemas']['InsightPeriodDto']
      createdAt: string
      updatedAt: string
      seen: boolean
      confidence: InsightConfidence
      feedback: InsightFeedback | null
      primaryAction: components['schemas']['InsightActionDto']
    }
    CardsCountDto: { count: number; available: boolean }
    /** Contagens dos chips, sempre sobre os ativos e sem filtro; Cartões disponível desde a insights-v2. */
    InsightCountsDto: {
      all: number
      important: number
      spending: number
      income: number
      health: number
      cards: components['schemas']['CardsCountDto']
    }
    InsightListResponse: {
      workspaceId: string
      algorithmVersion: string
      state: InsightDataState
      freshness: components['schemas']['InsightsFreshnessDto']
      period: components['schemas']['InsightPeriodDto']
      counts: components['schemas']['InsightCountsDto']
      items: components['schemas']['InsightItemDto'][]
      page: number
      pageSize: number
      totalItems: number
    }
    InsightRecommendationDto: { title: string; text: string }
    /** `value` decimal na unidade de `kind`; dinheiro com as casas da moeda. */
    InsightFigureDto: { code: string; label: string; kind: InsightFigureKind; value: string; from: string; to: string }
    InsightPointDto: { month: string; value: string; partial: boolean }
    InsightSeriesDto: { kind: InsightFigureKind; points: components['schemas']['InsightPointDto'][] }
    InsightContributorDto: {
      kind: 'MERCHANT' | 'CATEGORY' | 'TRANSACTION' | 'CHARGE' | 'FACTOR'
      key: string | null
      name: string
      amount: MoneyDto
      count: number | null
      share: string | null
      day: string | null
    }
    InsightLimitationDto: {
      code:
        | 'PARTIAL_MONTH'
        | 'SHORT_HISTORY'
        | 'UNCATEGORIZED'
        | 'ESTIMATED_SCORE'
        | 'IMPORT_LAG'
        | 'PARTIAL_INVOICE'
        | 'CARD_PAYMENTS'
        | 'ESTIMATED_FORECAST'
      text: string
    }
    InsightSourceDto: { source: string; from: string; to: string }
    InsightDimensionDto: { code: HealthDimensionCode; label: string; score: number | null }
    /** Detalhe (ADR-0023 §1), em qualquer estado. */
    InsightDetailResponse: {
      workspaceId: string
      algorithmVersion: string
      freshness: components['schemas']['InsightsFreshnessDto']
      insight: components['schemas']['InsightItemDto']
      recommendation: components['schemas']['InsightRecommendationDto'] | null
      figures: components['schemas']['InsightFigureDto'][]
      series: components['schemas']['InsightSeriesDto']
      contributors: components['schemas']['InsightContributorDto'][]
      comparison: string
      limitations: components['schemas']['InsightLimitationDto'][]
      sources: components['schemas']['InsightSourceDto'][]
      dataSources: ('CSV' | 'MANUAL' | 'OFX')[]
      actions: components['schemas']['InsightActionDto'][]
      related: components['schemas']['InsightItemDto'][]
      dimension: components['schemas']['InsightDimensionDto'] | null
    }
    /** Linha do "O que mudou?", formatada pelo backend. */
    InsightChangeDto: {
      insightId: string
      detector: InsightDetector
      type: InsightType
      label: string
      value: string
      detail: string
    }
    InsightHighlightsResponse: {
      workspaceId: string
      algorithmVersion: string
      state: InsightDataState
      freshness: components['schemas']['InsightsFreshnessDto']
      period: components['schemas']['InsightPeriodDto']
      attention: components['schemas']['InsightItemDto'][]
      changes: components['schemas']['InsightChangeDto'][]
      importantCount: number
    }
    InsightRelatedResponse: {
      workspaceId: string
      algorithmVersion: string
      freshness: components['schemas']['InsightsFreshnessDto']
      items: components['schemas']['InsightItemDto'][]
    }
    InsightFeedbackRequest: { value: InsightFeedback | null }
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
    HealthContextDto: {
      activeAccounts: number
      uncategorizedShare: string
      sources: ('CSV' | 'MANUAL' | 'OFX')[]
      /** Cartões ativos acompanhados (health-v2, ADR-0027). */
      activeCards: number
    }
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
      kind: HealthNextStepKind
      text: string
      evidence: string
      categoryCode: string | null
      month: string | null
      amount: components['schemas']['MoneyDto'] | null
      /** Só PAY_OVERDUE_INVOICE e REVIEW_CARD_UTILIZATION (ADR-0027 §4). */
      cardId: string | null
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
    /** Destino: conta ou cartão (ADR-0009 §19); exatamente um dos dois. */
    ImportResponse: {
      id: string
      workspaceId: string
      accountId: string | null
      cardId: string | null
      format: 'CSV' | 'OFX'
      status: 'PREVIEW' | 'CONFIRMED' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'EXPIRED'
      fileName: string
      fileSize: number
      /** `skipped`: pagamentos no arquivo do cartão; `reconciled`: parcelas de compras já lançadas (§23/§26). */
      lines: {
        total: number
        valid: number
        invalid: number
        duplicate: number
        skipped: number
        imported: number
        reconciled: number
        categorized: number
      }
      period: { from: string; to: string } | null
      /** Só o que o arquivo trouxe explicitamente (OFX `CCCLOSING`, §21). */
      statement: { dueDate: string | null; minimumPayment: components['schemas']['MoneyDto'] | null } | null
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
      status: 'VALID' | 'INVALID' | 'DUPLICATE' | 'SKIPPED' | 'IMPORTED' | 'RECONCILED'
      occurredOn: string | null
      amount: components['schemas']['MoneyDto'] | null
      /** No cartão, INFLOW = crédito e OUTFLOW = compra. */
      direction: 'INFLOW' | 'OUTFLOW' | null
      description: string | null
      issue: { field: string; code: string } | null
      transactionId: string | null
      /** Só em SKIPPED: `CARD_PAYMENT` (o pagamento é registrado pela conta). */
      skipReason: string | null
      installment: { number: number; count: number } | null
    }
    ImportRecordPageResponse: {
      items: components['schemas']['ImportRecordResponse'][]
      page: number
      pageSize: number
      totalItems: number
    }
    /**
     * Cadastro/edição do cartão (ADR-0026 §2): só os 4 últimos dígitos. Campo desconhecido (número completo, CVV,
     * validade) recusa a requisição com 400 MALFORMED_REQUEST.
     */
    CardRequest: {
      nickname: string
      institutionName?: string | null
      brand: CardBrand
      last4: string
      limit: components['schemas']['MoneyDto']
      closingDay: number
      dueDay: number
      defaultPaymentAccountId?: string | null
    }
    CardResponse: {
      id: string
      workspaceId: string
      nickname: string
      institutionName: string | null
      brand: CardBrand
      last4: string
      limit: components['schemas']['MoneyDto']
      closingDay: number
      dueDay: number
      defaultPaymentAccountId: string | null
      status: 'ACTIVE' | 'ARCHIVED'
      /** Vencimento (`YYYY-MM`) da primeira fatura acompanhada (ADR-0025 §15). */
      trackingStartDueMonth: string
      createdAt: string
      updatedAt: string
    }
    CardListResponse: { items: components['schemas']['CardResponse'][] }
    /**
     * Fatura pelo mês de vencimento (`YYYY-MM`). Valores e estado calculados no backend (ADR-0025 §5); `partial` = aberta,
     * pode mudar até o fechamento; `minimumPayment` só quando informado no arquivo.
     */
    InvoiceSummaryResponse: {
      dueMonth: string
      periodStart: string
      closingDate: string
      dueDate: string
      effectiveDueDate: string
      datesSource: 'CALCULATED' | 'IMPORTED'
      status: InvoiceStatus
      charges: components['schemas']['MoneyDto']
      credits: components['schemas']['MoneyDto']
      amount: components['schemas']['MoneyDto']
      paid: components['schemas']['MoneyDto']
      remaining: components['schemas']['MoneyDto']
      minimumPayment: components['schemas']['MoneyDto'] | null
      partial: boolean
    }
    InvoiceListResponse: { items: components['schemas']['InvoiceSummaryResponse'][] }
    InvoiceResponse: {
      invoice: components['schemas']['InvoiceSummaryResponse']
      itemCount: number
      asOf: string
      calculationVersion: string
    }
    InstallmentRef: { number: number; count: number }
    /** `installment` nulo em créditos. */
    InvoiceItemResponse: {
      kind: 'CHARGE' | 'CREDIT'
      transactionId: string
      occurredOn: string
      description: string
      categoryCode: string | null
      merchantName: string | null
      source: 'MANUAL' | 'IMPORT'
      installment: components['schemas']['InstallmentRef'] | null
      amount: components['schemas']['MoneyDto']
      cancelledAmount: components['schemas']['MoneyDto']
    }
    InvoiceItemPageResponse: {
      items: components['schemas']['InvoiceItemResponse'][]
      page: number
      pageSize: number
      totalItems: number
    }
    FutureInstallmentResponse: {
      transactionId: string
      description: string
      number: number
      count: number
      amount: components['schemas']['MoneyDto']
    }
    FutureInvoiceMonthResponse: {
      dueMonth: string
      committed: components['schemas']['MoneyDto']
      installments: components['schemas']['FutureInstallmentResponse'][]
    }
    /** Só o valor já comprometido em parcelas: nenhuma estimativa (ADR-0025 §10). */
    FutureInvoicesResponse: {
      months: components['schemas']['FutureInvoiceMonthResponse'][]
      totalCommitted: components['schemas']['MoneyDto']
      asOf: string
      calculationVersion: string
    }
    UtilizationCompositionResponse: {
      previousOutstanding: components['schemas']['MoneyDto']
      currentInvoice: components['schemas']['MoneyDto']
      futureInstallments: components['schemas']['MoneyDto']
    }
    /** Utilização do limite (ADR-0025 §9): `available` pode ser negativo; percentuais inteiros (HALF_EVEN). */
    UtilizationResponse: {
      limit: components['schemas']['MoneyDto']
      utilized: components['schemas']['MoneyDto']
      available: components['schemas']['MoneyDto']
      credit: components['schemas']['MoneyDto']
      utilizationPercent: number
      utilizationWithoutFuturePercent: number
      composition: components['schemas']['UtilizationCompositionResponse']
      asOf: string
      calculationVersion: string
    }
    /** Ponto da evolução da utilização: `current` só no de hoje; os demais são o último dia de um mês. */
    UtilizationPointResponse: {
      date: string
      utilized: components['schemas']['MoneyDto']
      limit: components['schemas']['MoneyDto']
      utilizationPercent: number
      current: boolean
    }
    /** Evolução da utilização (ADR-0029 §2), com o limite atual; datas antes do acompanhamento ficam fora. */
    UtilizationHistoryResponse: {
      points: components['schemas']['UtilizationPointResponse'][]
      asOf: string
      calculationVersion: string
    }
    CardSummaryResponse: {
      card: components['schemas']['CardResponse']
      currentInvoice: components['schemas']['InvoiceSummaryResponse']
      utilization: components['schemas']['UtilizationResponse']
      nextInvoiceCommitted: components['schemas']['MoneyDto']
    }
    /** Compra no cartão (ADR-0026 §10): `amount` é o total; as parcelas são calculadas no backend. */
    CardPurchaseRequest: {
      amount: components['schemas']['MoneyDto']
      occurredOn: string
      description: string
      /** 1 a 48; ausente = à vista. */
      installmentCount?: number | null
    }
    /** Pagamento de fatura: transferência da conta para o cartão. */
    CardPaymentRequest: {
      accountId: string
      amount: components['schemas']['MoneyDto']
      occurredOn: string
      /** Ausente = "Pagamento de fatura". */
      description?: string | null
    }
    /** Estorno ou crédito no cartão, opcionalmente vinculado a uma compra dele. */
    CardCreditRequest: {
      amount: components['schemas']['MoneyDto']
      occurredOn: string
      description: string
      refundOfTransactionId?: string | null
    }
    PurchaseInstallmentResponse: { number: number; amount: components['schemas']['MoneyDto']; dueMonth: string }
    CardPurchaseResponse: {
      transaction: components['schemas']['TransactionResponse']
      installments: components['schemas']['PurchaseInstallmentResponse'][]
      calculationVersion: string
    }
    CardTransactionResponse: { transaction: components['schemas']['TransactionResponse'] }
    /** "Marcar como pagamento de fatura" (ADR-0025 §7, adendo). */
    CardPaymentReconciliationRequest: { transactionId: string }
    CardPaymentReconciliationResponse: {
      payment: components['schemas']['TransactionResponse']
      /** true: vinculado a um pagamento já registrado, nada novo lançado. */
      matchedExisting: boolean
    }
    InstallmentItemResponse: {
      number: number
      dueMonth: string
      amount: components['schemas']['MoneyDto']
      cancelledAmount: components['schemas']['MoneyDto']
      /** BEFORE_TRACKING: fatura anterior ao início do acompanhamento (ADR-0025 §15). */
      status: 'PAID' | 'CURRENT' | 'FUTURE' | 'CANCELLED' | 'BEFORE_TRACKING'
    }
    ReservedLimitResponse: { dueMonth: string; amount: components['schemas']['MoneyDto'] }
    /** Peso do parcelamento na fatura aberta (ADR-0029 §5): `percent` inteiro HALF_EVEN, calculado no backend. */
    CurrentInvoiceShareResponse: {
      dueMonth: string
      installment: components['schemas']['MoneyDto']
      invoiceAmount: components['schemas']['MoneyDto']
      percent: number
    }
    /** Parcelamento de uma compra, com os estornos já aplicados (ADR-0025 §6/§8). */
    InstallmentPlanResponse: {
      transactionId: string
      description: string
      purchasedOn: string
      total: components['schemas']['MoneyDto']
      count: number
      installmentAmount: components['schemas']['MoneyDto']
      paidCount: number
      paidAmount: components['schemas']['MoneyDto']
      currentNumber: number | null
      remainingAfterCurrent: components['schemas']['MoneyDto']
      lastDueMonth: string
      status: 'ACTIVE' | 'FINISHED' | 'CANCELLED'
      installments: components['schemas']['InstallmentItemResponse'][]
      reservedLimit: components['schemas']['ReservedLimitResponse'][]
      /** Nulo sem parcela na fatura aberta ou com ela sem valor positivo. */
      currentInvoiceShare: components['schemas']['CurrentInvoiceShareResponse'] | null
    }
    InstallmentPlanListResponse: { items: components['schemas']['InstallmentPlanResponse'][] }
    /** Resumo dos cartões ativos (ADR-0026 §3); `nextDueDate` nulo sem fatura em aberto. */
    CardsSummaryResponse: {
      totalLimit: components['schemas']['MoneyDto']
      utilized: components['schemas']['MoneyDto']
      openInvoices: components['schemas']['MoneyDto']
      nextDueDate: string | null
      cards: components['schemas']['CardSummaryResponse'][]
      asOf: string
      calculationVersion: string
    }
    /** Freshness do snapshot do Forecast (ADR-0031 §9). */
    ForecastStatusResponse: {
      state: ForecastFreshnessState
      computedAt: string | null
      asOf: string | null
      analyticsRefreshedAt: string | null
    }
    /** Freshness no envelope das leituras do Forecast (ADR-0032 §1); `computedAt` e `asOf` nulos sem snapshot. */
    ForecastFreshnessDto: { state: ForecastFreshnessState; computedAt: string | null; asOf: string | null }
    ForecastRangeDto: { low: MoneyDto; high: MoneyDto }
    /** Só ids, meses e valores (nunca nomes): `month` nulo numa ocorrência, `refId` nulo numa amostra mensal. */
    ForecastEvidenceDto: { kind: ForecastEvidenceKind; month: string | null; refId: string | null; value: MoneyDto | null }
    /** `value = factPart + estimatedPart`; `low <= value <= high`; `partial` só em fato (ADR-0030 §1). */
    ForecastValueDto: {
      basis: ForecastBasis
      value: MoneyDto
      factPart: MoneyDto
      estimatedPart: MoneyDto
      range: components['schemas']['ForecastRangeDto']
      partial: boolean
      confidence: ForecastConfidence
      limitations: ForecastLimitation[]
      evidence: components['schemas']['ForecastEvidenceDto'][]
    }
    ForecastChannelDto: { kind: 'ACCOUNT' | 'CARD'; id: string }
    /** `seriesId` nulo se a série não existe mais. */
    ForecastPendingOccurrenceDto: {
      seriesId: string | null
      direction: ForecastSeriesDirection
      label: string | null
      date: string
      expected: MoneyDto
      range: components['schemas']['ForecastRangeDto']
      confidence: ForecastConfidence
      channel: components['schemas']['ForecastChannelDto']
    }
    /** `baseMonths` 0 = sem estimativa (só os fatos). */
    ForecastMonthDto: {
      month: string
      asOf: string
      baseMonths: number
      expense: components['schemas']['ForecastValueDto']
      income: components['schemas']['ForecastValueDto']
      net: components['schemas']['ForecastValueDto']
      pending: components['schemas']['ForecastPendingOccurrenceDto'][]
    }
    /** `month` nulo em NO_DATA e NOT_BUILT; `asOf` nulo sem snapshot. */
    ForecastMonthResponse: {
      state: ForecastDataState
      freshness: components['schemas']['ForecastFreshnessDto']
      algorithmVersion: string
      asOf: string | null
      month: components['schemas']['ForecastMonthDto'] | null
    }
    ForecastNextIncomeDto: {
      seriesId: string | null
      label: string | null
      date: string
      channel: components['schemas']['ForecastChannelDto']
      amount: components['schemas']['ForecastValueDto']
    }
    /** `nextIncome` nulo sem snapshot, sem histórico suficiente ou sem receita recorrente prevista. */
    ForecastNextIncomeResponse: {
      state: ForecastDataState
      freshness: components['schemas']['ForecastFreshnessDto']
      algorithmVersion: string
      asOf: string | null
      nextIncome: components['schemas']['ForecastNextIncomeDto'] | null
    }
    /** `seriesId` nulo nos eventos de fatura; `closingEstimate` só na fatura aberta (o "≈ no fechamento"). */
    ForecastUpcomingEventDto: {
      kind: ForecastUpcomingEventKind
      date: string
      seriesId: string | null
      label: string | null
      channel: components['schemas']['ForecastChannelDto']
      value: components['schemas']['ForecastValueDto']
      closingEstimate: components['schemas']['ForecastValueDto'] | null
    }
    /** A agenda de 30 dias completa e ordenada; `events` vazio sem snapshot. */
    ForecastUpcomingResponse: {
      state: ForecastDataState
      freshness: components['schemas']['ForecastFreshnessDto']
      algorithmVersion: string
      asOf: string | null
      events: components['schemas']['ForecastUpcomingEventDto'][]
    }
    /** `total.factPart` é o parcial (aberta) ou o comprometido (futuras); `estimatedPart`, as novas compras. */
    ForecastInvoiceDto: {
      cardId: string
      nickname: string | null
      kind: 'OPEN' | 'NEXT' | 'FUTURE'
      closingMonth: string
      dueMonth: string
      closingDate: string
      dueDate: string
      total: components['schemas']['ForecastValueDto']
    }
    ForecastInvoicesResponse: {
      state: ForecastDataState
      freshness: components['schemas']['ForecastFreshnessDto']
      algorithmVersion: string
      asOf: string | null
      invoices: components['schemas']['ForecastInvoiceDto'][]
    }
    ForecastBalancePointDto: { date: string; central: MoneyDto; low: MoneyDto; high: MoneyDto }
    ForecastTroughDto: {
      lowestCentral: components['schemas']['ForecastBalancePointDto']
      lowestLowBound: components['schemas']['ForecastBalancePointDto']
    }
    ForecastCashFlowMonthDto: {
      month: string
      opening: MoneyDto
      inflowFact: MoneyDto
      inflowEstimated: MoneyDto
      outflowFact: MoneyDto
      outflowEstimated: MoneyDto
      closing: components['schemas']['ForecastValueDto']
    }
    ForecastCashFlowDto: {
      from: string
      to: string
      months: components['schemas']['ForecastCashFlowMonthDto'][]
      trough: components['schemas']['ForecastTroughDto']
      troughNext30: components['schemas']['ForecastTroughDto']
      confidence: ForecastConfidence
      limitations: ForecastLimitation[]
      evidence: components['schemas']['ForecastEvidenceDto'][]
    }
    /** `cashFlow` nulo sem snapshot ou sem histórico suficiente. */
    ForecastCashFlowResponse: {
      state: ForecastDataState
      freshness: components['schemas']['ForecastFreshnessDto']
      algorithmVersion: string
      asOf: string | null
      cashFlow: components['schemas']['ForecastCashFlowDto'] | null
    }
    ForecastDetectedDto: {
      expectedDay: number
      expectedAmount: MoneyDto
      amountRange: components['schemas']['ForecastRangeDto']
      confidence: ForecastConfidence
    }
    /**
     * Série recorrente (ADR-0032 §2). Os valores de topo são os efetivos; `detected` guarda os detectados.
     * `eligibility`, `nextExpectedDate` e `realizedThisMonth` vêm do último snapshot e são nulos numa série ainda
     * fora dele.
     */
    ForecastRecurringSeriesDto: {
      id: string
      direction: ForecastSeriesDirection
      label: string | null
      channel: components['schemas']['ForecastChannelDto']
      periodicity: 'MONTHLY'
      expectedDay: number
      expectedAmount: MoneyDto
      amountRange: components['schemas']['ForecastRangeDto']
      detected: components['schemas']['ForecastDetectedDto']
      userAdjusted: boolean
      status: ForecastSeriesStatus
      origin: 'DETECTED' | 'USER'
      eligibility: ForecastSeriesEligibility | null
      nextExpectedDate: string | null
      realizedThisMonth: boolean | null
      occurrences: string[]
      algorithmVersion: string
    }
    ForecastRecurringListResponse: { items: components['schemas']['ForecastRecurringSeriesDto'][] }
    ForecastRecurringActionResponse: {
      series: components['schemas']['ForecastRecurringSeriesDto']
      forecastState: 'RECALCULATING'
    }
    /** Pelo menos um dos campos; o ausente mantém o valor atual. Valor positivo com até 2 casas; dia de 1 a 31. */
    ForecastAdjustmentRequest: { expectedAmount?: MoneyDto | null; expectedDay?: number | null }
    /** Origem do objetivo (ADR-0034 §1): `insightId` só com `INSIGHT`. */
    GoalOriginDto: { kind: GoalOriginKind; insightId: string | null }
    /** `ACCOUNT`: conta dedicada; `RESERVE`: as contas `SAVINGS` ativas, sem `accountId` (ADR-0034 §2.1). */
    GoalDestinationDto: { kind: GoalDestinationKind; accountId: string | null }
    /** Progresso, sempre fato (ADR-0034 §2.2): `saved` nunca negativo; `percent` com teto 99 até atingir o alvo. */
    GoalProgressDto: {
      basis: 'FACT'
      saved: MoneyDto
      percent: number
      remaining: MoneyDto
      reached: boolean
      destinationBalance: MoneyDto
      destinationState: GoalDestinationState
    }
    /** Objetivo (ADR-0035 §1). `deadline` como "2027-07"; a previsão (Estimado) entra na parte C. */
    GoalResponse: {
      id: string
      name: string
      type: GoalType
      status: GoalStatus
      origin: components['schemas']['GoalOriginDto']
      targetAmount: MoneyDto
      plannedMonthly: MoneyDto
      deadline: string | null
      sourceAccountId: string | null
      destination: components['schemas']['GoalDestinationDto']
      trackingStart: string
      createdAt: string
      completedAt: string | null
      completedAmount: MoneyDto | null
      progress: components['schemas']['GoalProgressDto']
      forecast: components['schemas']['GoalForecastDto']
    }
    /** Previsão de conclusão, sempre Estimado (ADR-0034 §4, ADR-0036 §1). Só `reason` em NO_FORECAST. */
    GoalForecastDto: {
      basis: 'ESTIMATED'
      status: GoalForecastStatus
      reason: GoalForecastReason | null
      completion: string | null
      contributionsLeft: number | null
      monthsLate: number | null
      monthsEarly: number | null
      range: components['schemas']['GoalForecastRange'] | null
      confidence: 'HIGH' | 'MEDIUM' | 'LOW' | null
      realPace: MoneyDto | null
      freshness: GoalForecastFreshness
    }
    GoalForecastRange: { optimistic: string; pessimistic: string | null; openEnded: boolean }
    /** Resumo sempre dos objetivos abertos, qualquer que seja o filtro (ADR-0035 §1). */
    GoalListSummary: {
      savedTotal: MoneyDto
      plannedMonthlyTotal: MoneyDto
      activeCount: number
      plannedIncomeShare: number | null
      forecastCount: number
      onTrackCount: number
      forecastFreshness: GoalForecastFreshness
    }
    GoalListResponse: {
      summary: components['schemas']['GoalListSummary']
      goals: components['schemas']['GoalResponse'][]
    }
    CreateGoalRequest: {
      name: string
      type: GoalType
      targetAmount: MoneyDto
      plannedMonthly: MoneyDto
      deadline?: string | null
      sourceAccountId?: string | null
      destinationAccountId?: string | null
      origin?: components['schemas']['GoalOriginRequest'] | null
    }
    GoalOriginRequest: { kind: GoalOriginKind; insightId?: string | null }
    UpdateGoalRequest: {
      name: string
      targetAmount: MoneyDto
      plannedMonthly: MoneyDto
      deadline?: string | null
      sourceAccountId?: string | null
      destinationAccountId?: string | null
    }
    ApplyGoalPlanRequest: { plannedMonthly: MoneyDto; deadline?: string | null }
    /** "Registrar aporte" (ADR-0035 §2): `destinationAccountId` só na reserva. */
    GoalContributionRequest: {
      sourceAccountId: string
      destinationAccountId?: string | null
      amount: MoneyDto
      occurredOn: string
      description?: string | null
    }
    GoalOpeningBalance: { date: string; amount: MoneyDto }
    /** Movimento do destino (ADR-0034 §2.3), valor sempre positivo. */
    GoalMovementDto: {
      transactionId: string
      kind: GoalMovementKind
      direction: 'IN' | 'OUT'
      occurredOn: string
      amount: MoneyDto
      counterpartAccountId: string | null
    }
    GoalContributionsResponse: {
      openingBalance: components['schemas']['GoalOpeningBalance']
      movements: components['schemas']['GoalMovementDto'][]
      page: number
      pageSize: number
      totalElements: number
    }
    RecentContributionItem: {
      goalId: string
      transactionId: string
      occurredOn: string
      amount: MoneyDto
      sourceAccountId: string | null
    }
    RecentContributionsResponse: { contributions: components['schemas']['RecentContributionItem'][] }
    /** De 1 a 5 cenários (ADR-0036 §2); campos ausentes usam o plano do objetivo. */
    SimulateGoalRequest: { scenarios: components['schemas']['SimulationScenarioRequest'][] }
    SimulationScenarioRequest: {
      monthlyAmount?: MoneyDto | null
      deadline?: string | null
      reduction?: components['schemas']['SimulationReductionRequest'] | null
    }
    SimulationReductionRequest: { categoryCode: string; amount: MoneyDto }
    /** Sempre Simulado, nunca gravado (ADR-0034 §5). */
    SimulationResponse: {
      basis: 'SIMULATED'
      remaining: MoneyDto
      start: string
      averageIncome: MoneyDto | null
      assumptions: ('EQUAL_MONTHLY_CONTRIBUTIONS' | 'NO_YIELD')[]
      scenarios: components['schemas']['SimulationScenarioDto'][]
    }
    SimulationScenarioDto: {
      monthlyAmount: MoneyDto
      completion: string | null
      contributions: number
      firstContribution: string
      lastContribution: components['schemas']['SimulationLastContribution'] | null
      deadline: components['schemas']['SimulationDeadline'] | null
      requiredMonthlyForDeadline: MoneyDto | null
      incomeShare: number | null
      tooFar: boolean
      reduction: components['schemas']['SimulationReduction'] | null
    }
    SimulationLastContribution: { month: string; amount: MoneyDto }
    SimulationDeadline: { month: string; within: boolean | null; monthsFromDeadline: number | null }
    SimulationReduction: { categoryCode: string; amount: MoneyDto }
    PreviewGoalRequest: {
      type: GoalType
      targetAmount?: MoneyDto | null
      plannedMonthly: MoneyDto
      deadline?: string | null
      destinationAccountId?: string | null
    }
    /** `alreadySaved` é fato; `simulation` é Simulado (ADR-0036 §3). */
    GoalPreviewResponse: {
      alreadySaved: MoneyDto
      suggestedTarget: MoneyDto | null
      target: MoneyDto | null
      simulation: components['schemas']['SimulationResponse'] | null
    }
    /** Gráfico do D-GoalDetail: fato e estimativa em séries separadas (ADR-0036 §5). */
    GoalProjectionResponse: {
      target: MoneyDto
      deadline: string | null
      actual: components['schemas']['GoalProjectionSeries']
      projected: components['schemas']['GoalProjectionSeries']
      forecast: components['schemas']['GoalForecastDto']
    }
    GoalProjectionSeries: { basis: 'FACT' | 'ESTIMATED'; points: components['schemas']['GoalProjectionPoint'][] }
    GoalProjectionPoint: { month: string; amount: MoneyDto }
  }
}

export type CardBrand = 'VISA' | 'MASTERCARD' | 'ELO' | 'AMEX' | 'HIPERCARD' | 'OTHER'
export type InvoiceStatus = 'FUTURE' | 'OPEN' | 'CLOSED' | 'PARTIALLY_PAID' | 'PAID' | 'OVERDUE'

type MoneyDto = components['schemas']['MoneyDto']
export type ForecastFreshnessState = 'UP_TO_DATE' | 'RECALCULATING' | 'NOT_BUILT'
export type ForecastDataState = 'READY' | 'NO_DATA' | 'INSUFFICIENT_HISTORY' | 'NOT_BUILT'
export type ForecastBasis = 'FACT' | 'ESTIMATED'
export type ForecastConfidence = 'HIGH' | 'MEDIUM' | 'LOW'
export type ForecastLimitation =
  | 'INSUFFICIENT_HISTORY'
  | 'SHORT_HISTORY'
  | 'HIGH_VARIABILITY'
  | 'UNCATEGORIZED'
  | 'PARTIAL_CARD_HISTORY'
export type ForecastEvidenceKind = 'SAMPLE_MONTH' | 'SERIES_OCCURRENCE' | 'BASE_INVOICE'
export type ForecastUpcomingEventKind =
  | 'INVOICE_PARTIAL'
  | 'INVOICE_OPEN'
  | 'INVOICE_OVERDUE'
  | 'RECURRING_INCOME'
  | 'RECURRING_EXPENSE'
export type ForecastSeriesDirection = 'INCOME' | 'EXPENSE'
export type ForecastSeriesStatus = 'DETECTED' | 'CONFIRMED' | 'IGNORED'
export type ForecastSeriesEligibility = 'ELIGIBLE' | 'STALE' | 'NOT_ELIGIBLE'

type ForecastErrors = {
  400: { content: { 'application/json': components['schemas']['ApiError'] } }
  401: { content: { 'application/json': components['schemas']['ApiError'] } }
  404: { content: { 'application/json': components['schemas']['ApiError'] } }
}
type ForecastSeriesPath = { workspaceId: string; seriesId: string }
type ForecastActionResult = {
  200: { content: { 'application/json': components['schemas']['ForecastRecurringActionResponse'] } }
} & ForecastErrors
type ForecastSeriesResult = {
  200: { content: { 'application/json': components['schemas']['ForecastRecurringSeriesDto'] } }
} & ForecastErrors

/** As rotas de despesas e receitas recorrentes têm o mesmo formato (ADR-0032 §2). */
type ForecastRecurringRoutes = {
  get: {
    parameters: { query?: { status?: ForecastSeriesStatus }; path: { workspaceId: string } }
    responses: { 200: { content: { 'application/json': components['schemas']['ForecastRecurringListResponse'] } } } & ForecastErrors
  }
}
type ForecastRecurringSeriesRoutes = {
  get: { parameters: { path: ForecastSeriesPath }; responses: ForecastSeriesResult }
}
type ForecastRecurringAdjustmentRoutes = {
  put: {
    parameters: { path: ForecastSeriesPath }
    requestBody: { content: { 'application/json': components['schemas']['ForecastAdjustmentRequest'] } }
    responses: ForecastActionResult
  }
  delete: { parameters: { path: ForecastSeriesPath }; responses: ForecastActionResult }
}
type ForecastRecurringActionRoute = { post: { parameters: { path: ForecastSeriesPath }; responses: ForecastActionResult } }

export type GoalType = 'EMERGENCY_RESERVE' | 'PURCHASE' | 'CUSTOM'
export type GoalStatus = 'ACTIVE' | 'PAUSED' | 'COMPLETED' | 'ARCHIVED'
export type GoalOriginKind = 'MANUAL' | 'INSIGHT' | 'HEALTH'
export type GoalDestinationKind = 'ACCOUNT' | 'RESERVE'
export type GoalDestinationState = 'OK' | 'ACCOUNT_ARCHIVED' | 'NO_RESERVE_ACCOUNT'
export type GoalMovementKind = 'CONTRIBUTION' | 'WITHDRAWAL' | 'OTHER'
export type GoalForecastStatus = 'ON_TRACK' | 'LATE' | 'NO_DEADLINE' | 'REACHED' | 'NO_FORECAST'
export type GoalForecastReason = 'FORECAST_NOT_READY' | 'NO_PLAN' | 'NOT_ACTIVE' | 'TOO_FAR'
export type GoalForecastFreshness = 'UP_TO_DATE' | 'RECALCULATING' | 'NOT_BUILT'

type GoalErrors = {
  400: { content: { 'application/json': components['schemas']['ApiError'] } }
  401: { content: { 'application/json': components['schemas']['ApiError'] } }
  404: { content: { 'application/json': components['schemas']['ApiError'] } }
  409: { content: { 'application/json': components['schemas']['ApiError'] } }
}
type GoalPath = { workspaceId: string; goalId: string }
type GoalResult = { 200: { content: { 'application/json': components['schemas']['GoalResponse'] } } } & GoalErrors
type GoalActionRoute = { post: { parameters: { path: GoalPath }; responses: GoalResult } }
export type AnalysisFreshnessState = 'UP_TO_DATE' | 'UPDATING' | 'NOT_BUILT'
export type HealthFreshnessState = 'UP_TO_DATE' | 'RECALCULATING' | 'NOT_BUILT'
export type InsightsFreshnessState = 'UP_TO_DATE' | 'UPDATING' | 'NOT_BUILT'
export type InsightDataState = 'NO_DATA' | 'INSUFFICIENT_HISTORY' | 'READY'
export type InsightType = 'SPENDING' | 'INCOME' | 'HEALTH' | 'CARDS'
export type InsightImportance = 'IMPORTANT' | 'INFORMATIVE'
export type InsightStatus = 'ACTIVE' | 'RESOLVED' | 'EXPIRED' | 'DISMISSED'
export type InsightConfidence = 'HIGH' | 'MEDIUM' | 'LOW'
export type InsightFeedback = 'USEFUL' | 'NOT_USEFUL'
export type InsightFigureKind = 'MONEY' | 'PERCENT' | 'COUNT' | 'MONTHS' | 'POINTS' | 'RATIO'
export type InsightDetector =
  | 'CATEGORY_SPIKE'
  | 'SPENDING_ABOVE_PATTERN'
  | 'NEW_RECURRING_MERCHANT'
  | 'UNUSUAL_EXPENSE'
  | 'RECURRING_INCOME_MISSING'
  | 'RECURRING_INCOME_LOWER'
  | 'NEW_INCOME_SOURCE'
  | 'HEALTH_SCORE_CHANGE'
  | 'HEALTH_DIMENSION_CHANGE'
  | 'LOW_RESERVE'
  | 'UNCATEGORIZED_SHARE'
  | 'INVOICE_ABOVE_PATTERN'
  | 'CARD_UTILIZATION_HIGH'
  | 'INVOICE_DUE'
  | 'BALANCE_MAY_GO_NEGATIVE'
export type InsightActionKind =
  | 'VIEW_TRANSACTIONS'
  | 'VIEW_CATEGORY'
  | 'VIEW_ANALYSIS'
  | 'VIEW_MERCHANTS'
  | 'VIEW_TRANSACTION'
  | 'IMPORT_STATEMENT'
  | 'VIEW_INCOME'
  | 'VIEW_HEALTH'
  | 'VIEW_ACCOUNTS'
  | 'CATEGORIZE'
  | 'VIEW_INVOICE'
  | 'VIEW_CARD'
  | 'RECORD_CARD_PAYMENT'
  | 'VIEW_UPCOMING_EVENTS'
  | 'CREATE_SAVINGS_GOAL'
  | 'ASK_COPILOT'
export type HealthState = 'READY' | 'INSUFFICIENT_DATA' | 'NO_DATA'
export type HealthNextStepKind =
  | 'PAY_OVERDUE_INVOICE'
  | 'BUILD_RESERVE'
  | 'ADD_RESERVE_ACCOUNT'
  | 'REVIEW_CATEGORY'
  | 'REVIEW_CARD_UTILIZATION'
  | 'CATEGORIZE_TRANSACTIONS'
  | 'REVIEW_MONTH'
export type UpcomingInvoiceEventKind = 'INVOICE_CLOSING' | 'INVOICE_DUE'
export type UpcomingInvoiceEventStatus = 'PARTIAL' | 'CLOSED' | 'OVERDUE'
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

type InsightPath = { workspaceId: string; insightId: string }

/** Ações sobre um insight (ADR-0023 §3): 409 só em dispensar um insight que não está mais ativo. */
type InsightResult = {
  200: { content: { 'application/json': components['schemas']['InsightItemDto'] } }
  400: { content: { 'application/json': components['schemas']['ApiError'] } }
  401: { content: { 'application/json': components['schemas']['ApiError'] } }
  404: { content: { 'application/json': components['schemas']['ApiError'] } }
  409: { content: { 'application/json': components['schemas']['ApiError'] } }
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

type CardErrors = {
  400: { content: { 'application/json': components['schemas']['ApiError'] } }
  404: { content: { 'application/json': components['schemas']['ApiError'] } }
  409: { content: { 'application/json': components['schemas']['ApiError'] } }
}

type CardPath = { workspaceId: string; cardId: string }
type CardInvoicePath = { workspaceId: string; cardId: string; dueMonth: string }
type CardResult = {
  200: { content: { 'application/json': components['schemas']['CardResponse'] } }
} & CardErrors

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
          cardId?: string
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
          cardId?: string
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
  '/api/v1/workspaces/{workspaceId}/insights/status': {
    get: {
      parameters: { path: { workspaceId: string } }
      responses: { 200: { content: { 'application/json': components['schemas']['InsightsStatusResponse'] } } } & AnalysisErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/insights': {
    get: {
      parameters: {
        query?: {
          importance?: InsightImportance
          type?: InsightType | 'CARDS'
          state?: 'ACTIVE' | 'DISMISSED'
          page?: number
          pageSize?: number
        }
        path: { workspaceId: string }
      }
      responses: { 200: { content: { 'application/json': components['schemas']['InsightListResponse'] } } } & AnalysisErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/insights/highlights': {
    get: {
      parameters: { path: { workspaceId: string } }
      responses: { 200: { content: { 'application/json': components['schemas']['InsightHighlightsResponse'] } } } & AnalysisErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/insights/related': {
    get: {
      parameters: { query?: { transactionId?: string; dimension?: HealthDimensionCode }; path: { workspaceId: string } }
      responses: { 200: { content: { 'application/json': components['schemas']['InsightRelatedResponse'] } } } & AnalysisErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/insights/{insightId}': {
    get: {
      parameters: { path: InsightPath }
      responses: { 200: { content: { 'application/json': components['schemas']['InsightDetailResponse'] } } } & AnalysisErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/insights/{insightId}/seen': {
    post: { parameters: { path: InsightPath }; responses: InsightResult }
  }
  '/api/v1/workspaces/{workspaceId}/insights/{insightId}/dismiss': {
    post: { parameters: { path: InsightPath }; responses: InsightResult }
  }
  '/api/v1/workspaces/{workspaceId}/insights/{insightId}/feedback': {
    put: {
      parameters: { path: InsightPath }
      requestBody: { content: { 'application/json': components['schemas']['InsightFeedbackRequest'] } }
      responses: InsightResult
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
        /** Exatamente um: conta (extrato) ou cartão (fatura). */
        query: { accountId?: string; cardId?: string }
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
        query?: {
          status?: 'VALID' | 'INVALID' | 'DUPLICATE' | 'SKIPPED' | 'IMPORTED' | 'RECONCILED'
          page?: number
          pageSize?: number
        }
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
  '/api/v1/workspaces/{workspaceId}/cards': {
    get: {
      parameters: { query?: { includeArchived?: boolean }; path: { workspaceId: string } }
      responses: { 200: { content: { 'application/json': components['schemas']['CardListResponse'] } } } & CardErrors
    }
    post: {
      parameters: { path: { workspaceId: string } }
      requestBody: { content: { 'application/json': components['schemas']['CardRequest'] } }
      responses: { 201: { content: { 'application/json': components['schemas']['CardResponse'] } } } & CardErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/cards/summary': {
    get: {
      parameters: { path: { workspaceId: string } }
      responses: {
        200: { content: { 'application/json': components['schemas']['CardsSummaryResponse'] } }
      } & CardErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/cards/{cardId}': {
    get: { parameters: { path: CardPath }; responses: CardResult }
    put: {
      parameters: { path: CardPath }
      requestBody: { content: { 'application/json': components['schemas']['CardRequest'] } }
      responses: CardResult
    }
  }
  '/api/v1/workspaces/{workspaceId}/cards/{cardId}/archive': {
    post: { parameters: { path: CardPath }; responses: CardResult }
  }
  '/api/v1/workspaces/{workspaceId}/cards/{cardId}/reactivate': {
    post: { parameters: { path: CardPath }; responses: CardResult }
  }
  '/api/v1/workspaces/{workspaceId}/cards/{cardId}/invoices': {
    get: {
      parameters: { query?: { from?: string; to?: string }; path: CardPath }
      responses: {
        200: { content: { 'application/json': components['schemas']['InvoiceListResponse'] } }
      } & CardErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/cards/{cardId}/invoices/current': {
    get: {
      parameters: { path: CardPath }
      responses: { 200: { content: { 'application/json': components['schemas']['InvoiceResponse'] } } } & CardErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/cards/{cardId}/invoices/future': {
    get: {
      parameters: { path: CardPath }
      responses: {
        200: { content: { 'application/json': components['schemas']['FutureInvoicesResponse'] } }
      } & CardErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/cards/{cardId}/invoices/{dueMonth}': {
    get: {
      parameters: { path: CardInvoicePath }
      responses: { 200: { content: { 'application/json': components['schemas']['InvoiceResponse'] } } } & CardErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/cards/{cardId}/invoices/{dueMonth}/items': {
    get: {
      parameters: { query?: { page?: number; pageSize?: number }; path: CardInvoicePath }
      responses: {
        200: { content: { 'application/json': components['schemas']['InvoiceItemPageResponse'] } }
      } & CardErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/cards/{cardId}/purchases': {
    post: {
      parameters: { header: { 'Idempotency-Key': string }; path: CardPath }
      requestBody: { content: { 'application/json': components['schemas']['CardPurchaseRequest'] } }
      responses: {
        200: { content: { 'application/json': components['schemas']['CardPurchaseResponse'] } }
        201: { content: { 'application/json': components['schemas']['CardPurchaseResponse'] } }
        422: { content: { 'application/json': components['schemas']['ApiError'] } }
      } & CardErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/cards/{cardId}/payments': {
    post: {
      parameters: { header: { 'Idempotency-Key': string }; path: CardPath }
      requestBody: { content: { 'application/json': components['schemas']['CardPaymentRequest'] } }
      responses: {
        200: { content: { 'application/json': components['schemas']['CardTransactionResponse'] } }
        201: { content: { 'application/json': components['schemas']['CardTransactionResponse'] } }
        422: { content: { 'application/json': components['schemas']['ApiError'] } }
      } & CardErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/cards/{cardId}/payments/reconcile': {
    post: {
      parameters: { path: CardPath }
      requestBody: { content: { 'application/json': components['schemas']['CardPaymentReconciliationRequest'] } }
      responses: {
        200: { content: { 'application/json': components['schemas']['CardPaymentReconciliationResponse'] } }
        201: { content: { 'application/json': components['schemas']['CardPaymentReconciliationResponse'] } }
      } & CardErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/cards/{cardId}/credits': {
    post: {
      parameters: { header: { 'Idempotency-Key': string }; path: CardPath }
      requestBody: { content: { 'application/json': components['schemas']['CardCreditRequest'] } }
      responses: {
        200: { content: { 'application/json': components['schemas']['CardTransactionResponse'] } }
        201: { content: { 'application/json': components['schemas']['CardTransactionResponse'] } }
        422: { content: { 'application/json': components['schemas']['ApiError'] } }
      } & CardErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/cards/{cardId}/installments': {
    get: {
      parameters: { query?: { status?: 'ACTIVE' | 'FINISHED' | 'ALL' }; path: CardPath }
      responses: {
        200: { content: { 'application/json': components['schemas']['InstallmentPlanListResponse'] } }
      } & CardErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/cards/{cardId}/installments/{transactionId}': {
    get: {
      parameters: { path: { workspaceId: string; cardId: string; transactionId: string } }
      responses: {
        200: { content: { 'application/json': components['schemas']['InstallmentPlanResponse'] } }
      } & CardErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/cards/{cardId}/utilization': {
    get: {
      parameters: { path: CardPath }
      responses: {
        200: { content: { 'application/json': components['schemas']['UtilizationResponse'] } }
      } & CardErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/cards/{cardId}/utilization/history': {
    get: {
      parameters: { query?: { months?: number }; path: CardPath }
      responses: {
        200: { content: { 'application/json': components['schemas']['UtilizationHistoryResponse'] } }
      } & CardErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/forecast/status': {
    get: {
      parameters: { path: { workspaceId: string } }
      responses: { 200: { content: { 'application/json': components['schemas']['ForecastStatusResponse'] } } } & ForecastErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/forecast/month': {
    get: {
      parameters: { path: { workspaceId: string } }
      responses: { 200: { content: { 'application/json': components['schemas']['ForecastMonthResponse'] } } } & ForecastErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/forecast/next-income': {
    get: {
      parameters: { path: { workspaceId: string } }
      responses: { 200: { content: { 'application/json': components['schemas']['ForecastNextIncomeResponse'] } } } & ForecastErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/forecast/upcoming': {
    get: {
      parameters: { path: { workspaceId: string } }
      responses: { 200: { content: { 'application/json': components['schemas']['ForecastUpcomingResponse'] } } } & ForecastErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/forecast/invoices': {
    get: {
      parameters: { query?: { cardId?: string }; path: { workspaceId: string } }
      responses: { 200: { content: { 'application/json': components['schemas']['ForecastInvoicesResponse'] } } } & ForecastErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/forecast/cash-flow': {
    get: {
      parameters: { path: { workspaceId: string } }
      responses: { 200: { content: { 'application/json': components['schemas']['ForecastCashFlowResponse'] } } } & ForecastErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/recurring-expenses': ForecastRecurringRoutes
  '/api/v1/workspaces/{workspaceId}/recurring-expenses/{seriesId}': ForecastRecurringSeriesRoutes
  '/api/v1/workspaces/{workspaceId}/recurring-expenses/{seriesId}/confirm': ForecastRecurringActionRoute
  '/api/v1/workspaces/{workspaceId}/recurring-expenses/{seriesId}/ignore': ForecastRecurringActionRoute
  '/api/v1/workspaces/{workspaceId}/recurring-expenses/{seriesId}/adjustment': ForecastRecurringAdjustmentRoutes
  '/api/v1/workspaces/{workspaceId}/recurring-incomes': ForecastRecurringRoutes
  '/api/v1/workspaces/{workspaceId}/recurring-incomes/{seriesId}': ForecastRecurringSeriesRoutes
  '/api/v1/workspaces/{workspaceId}/recurring-incomes/{seriesId}/confirm': ForecastRecurringActionRoute
  '/api/v1/workspaces/{workspaceId}/recurring-incomes/{seriesId}/ignore': ForecastRecurringActionRoute
  '/api/v1/workspaces/{workspaceId}/recurring-incomes/{seriesId}/adjustment': ForecastRecurringAdjustmentRoutes
  '/api/v1/workspaces/{workspaceId}/goals': {
    get: {
      parameters: { query?: { status?: GoalStatus }; path: { workspaceId: string } }
      responses: { 200: { content: { 'application/json': components['schemas']['GoalListResponse'] } } } & GoalErrors
    }
    post: {
      parameters: { path: { workspaceId: string } }
      requestBody: { content: { 'application/json': components['schemas']['CreateGoalRequest'] } }
      responses: { 201: { content: { 'application/json': components['schemas']['GoalResponse'] } } } & GoalErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/goals/recent-contributions': {
    get: {
      parameters: { query?: { limit?: number }; path: { workspaceId: string } }
      responses: {
        200: { content: { 'application/json': components['schemas']['RecentContributionsResponse'] } }
      } & GoalErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/goals/{goalId}': {
    get: { parameters: { path: GoalPath }; responses: GoalResult }
    put: {
      parameters: { path: GoalPath }
      requestBody: { content: { 'application/json': components['schemas']['UpdateGoalRequest'] } }
      responses: GoalResult
    }
  }
  '/api/v1/workspaces/{workspaceId}/goals/{goalId}/plan': {
    put: {
      parameters: { path: GoalPath }
      requestBody: { content: { 'application/json': components['schemas']['ApplyGoalPlanRequest'] } }
      responses: GoalResult
    }
  }
  '/api/v1/workspaces/{workspaceId}/goals/simulations/preview': {
    post: {
      parameters: { path: { workspaceId: string } }
      requestBody: { content: { 'application/json': components['schemas']['PreviewGoalRequest'] } }
      responses: { 200: { content: { 'application/json': components['schemas']['GoalPreviewResponse'] } } } & GoalErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/goals/{goalId}/simulations': {
    post: {
      parameters: { path: GoalPath }
      requestBody: { content: { 'application/json': components['schemas']['SimulateGoalRequest'] } }
      responses: { 200: { content: { 'application/json': components['schemas']['SimulationResponse'] } } } & GoalErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/goals/{goalId}/projection': {
    get: {
      parameters: { path: GoalPath }
      responses: {
        200: { content: { 'application/json': components['schemas']['GoalProjectionResponse'] } }
      } & GoalErrors
    }
  }
  '/api/v1/workspaces/{workspaceId}/goals/{goalId}/pause': GoalActionRoute
  '/api/v1/workspaces/{workspaceId}/goals/{goalId}/resume': GoalActionRoute
  '/api/v1/workspaces/{workspaceId}/goals/{goalId}/complete': GoalActionRoute
  '/api/v1/workspaces/{workspaceId}/goals/{goalId}/archive': GoalActionRoute
  '/api/v1/workspaces/{workspaceId}/goals/{goalId}/contributions': {
    get: {
      parameters: { query?: { page?: number; pageSize?: number }; path: GoalPath }
      responses: {
        200: { content: { 'application/json': components['schemas']['GoalContributionsResponse'] } }
      } & GoalErrors
    }
    post: {
      parameters: { header: { 'Idempotency-Key': string }; path: GoalPath }
      requestBody: { content: { 'application/json': components['schemas']['GoalContributionRequest'] } }
      responses: {
        200: { content: { 'application/json': components['schemas']['TransactionResponse'] } }
        201: { content: { 'application/json': components['schemas']['TransactionResponse'] } }
        422: { content: { 'application/json': components['schemas']['ApiError'] } }
      } & GoalErrors
    }
  }
}
