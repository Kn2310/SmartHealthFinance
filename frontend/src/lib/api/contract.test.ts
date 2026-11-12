// @vitest-environment node
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  ACCOUNT_NAME_MAX_LENGTH,
  CARD_BRANDS,
  CARD_NICKNAME_MAX_LENGTH,
  CARD_STATUSES,
  CONSENT_PURPOSES,
  COPILOT_ACTION_KINDS,
  COPILOT_ANSWER_CODES,
  COPILOT_ANSWER_TYPES,
  COPILOT_CONFIDENCES,
  COPILOT_CONTEXT_KINDS,
  COPILOT_FEEDBACK_RATINGS,
  COPILOT_FEEDBACK_REASONS,
  COPILOT_INTENTS,
  COPILOT_MAX_PREVIOUS_QUESTIONS,
  COPILOT_NOTICES,
  COPILOT_QUESTION_MAX_LENGTH,
  COPILOT_SEGMENT_KINDS,
  COPILOT_VALIDATIONS,
  COPILOT_VALUE_KINDS,
  CONSENT_STATES,
  CONSENT_TERMS_VERSIONS,
  GOAL_MAX_SCENARIOS,
  GOAL_NAME_MAX_LENGTH,
  GOAL_ORIGIN_KINDS,
  GOAL_STATUSES,
  GOAL_TYPES,
  INSTALLMENT_PLAN_STATUSES,
  INSTALLMENT_STATUSES,
  INVOICE_DATES_SOURCES,
  INVOICE_ITEM_KINDS,
  MAX_INSTALLMENTS,
  INVOICE_STATUSES,
  CATEGORY_CODE_MAX_LENGTH,
  CATEGORY_NATURES,
  ACCOUNT_STATUSES,
  ACCOUNT_TYPES,
  ADJUSTMENT_DIRECTIONS,
  IMPORT_FORMATS,
  INSTITUTION_NAME_MAX_LENGTH,
  IDEMPOTENCY_KEY_MAX_LENGTH,
  TRANSACTION_DESCRIPTION_MAX_LENGTH,
  TRANSACTION_MAX_PAGE_SIZE,
  TRANSACTION_SEARCH_MAX_LENGTH,
  IMPORT_STATUSES,
  MOVEMENT_FLOWS,
  RECORD_DIRECTIONS,
  RECORD_STATUSES,
  TRANSACTION_SOURCES,
  OVERVIEW_STATES,
  PERIOD_TYPES,
  RECORD_FIELDS,
  UPCOMING_INVOICE_EVENT_KINDS,
  UPCOMING_INVOICE_EVENT_STATUSES,
  TRANSACTION_STATUSES,
  TRANSACTION_TYPES,
} from './contract'

/**
 * Detecção de drift do contrato manual (`schema.d.ts`) contra o código-fonte REAL do backend (monorepo).
 * O OpenAPI do springdoc não serve para isso hoje: os DTOs expõem enums como String e não declaram nulos, então o
 * schema gerado perderia as uniões e a distinção null ≠ zero. Ver ADR-0007 §6.
 */
const JAVA = resolve(__dirname, '../../../../backend/src/main/java/com/smarthealthfinance')
const hasBackend = existsSync(JAVA)

const read = (path: string) => readFileSync(resolve(JAVA, path), 'utf8').replace(/\/\*[\s\S]*?\*\/|\/\/.*$/gm, '')

function javaEnum(path: string): string[] {
  // Constantes até o `;` (enum com métodos) ou até o `}` (enum só com constantes).
  const body = /enum\s+\w+\s*\{([^;}]*)[;}]/.exec(read(path))?.[1] ?? ''
  return body.split(',').map((c) => c.trim()).filter(Boolean)
}

function javaRecords(path: string): Record<string, string[]> {
  const records: Record<string, string[]> = {}
  // Anotações com parênteses (`@Size(max = …)` nos requests) saem antes de ler os parâmetros.
  const source = read(path).replace(/@\w+(?:\([^)]*\))?/g, '')
  for (const [, name, params] of source.matchAll(/record\s+(\w+)\s*\(([^)]*)\)/g)) {
    records[name!] = params!
      .split(',')
      .map((p) => p.trim().split(/\s+/).pop()!)
      .filter(Boolean)
  }
  return records
}

const sorted = (values: readonly string[]) => [...values].sort()

describe.skipIf(!hasBackend)('contrato do Overview × backend (drift)', () => {
  it.each([
    ['overview/domain/enums/OverviewState.java', OVERVIEW_STATES],
    ['overview/domain/enums/UpcomingEventKind.java', UPCOMING_INVOICE_EVENT_KINDS],
    ['overview/domain/enums/UpcomingEventStatus.java', UPCOMING_INVOICE_EVENT_STATUSES],
    ['overview/domain/enums/PeriodType.java', PERIOD_TYPES],
    ['overview/domain/enums/MovementFlow.java', MOVEMENT_FLOWS],
    ['accounts/domain/enums/AccountType.java', ACCOUNT_TYPES],
    ['transactions/domain/enums/TransactionType.java', TRANSACTION_TYPES],
    ['transactions/domain/enums/TransactionStatus.java', TRANSACTION_STATUSES],
    ['transactions/domain/enums/AdjustmentDirection.java', ADJUSTMENT_DIRECTIONS],
    ['transactions/domain/enums/CategoryNature.java', CATEGORY_NATURES],
  ])('enum %s', (path, frontend) => {
    expect(sorted(frontend)).toEqual(sorted(javaEnum(path)))
  })

  it('records de OverviewResponse têm exatamente os campos do contrato manual', () => {
    const records = javaRecords('overview/presentation/dto/response/OverviewResponse.java')
    for (const name of [
      'OverviewResponse', 'Period', 'Summary', 'CashFlow', 'Transfers', 'AccountSummary', 'AccountRef', 'RecentTransaction',
      'CardRef', 'InvoicesToPay', 'InvoicesNextDue', 'UpcomingInvoiceEvent',
    ] as const) {
      expect(sorted(records[name] ?? []), name).toEqual(sorted(RECORD_FIELDS[name]))
    }
  })

  it.each([
    ['transactions/domain/enums/TransactionSource.java', TRANSACTION_SOURCES],
    ['accounts/domain/enums/AccountStatus.java', ACCOUNT_STATUSES],
    ['ingestion/domain/enums/ImportFormat.java', IMPORT_FORMATS],
    ['ingestion/domain/enums/ImportStatus.java', IMPORT_STATUSES],
    ['ingestion/domain/enums/RecordStatus.java', RECORD_STATUSES],
  ])('enum %s', (path, frontend) => {
    expect(sorted(frontend)).toEqual(sorted(javaEnum(path)))
  })

  it('records de Accounts e Imports têm exatamente os campos do contrato manual', () => {
    const files: [string, (keyof typeof RECORD_FIELDS)[]][] = [
      ['accounts/presentation/dto/response/AccountResponse.java', ['AccountResponse']],
      ['accounts/presentation/dto/response/AccountListResponse.java', ['AccountListResponse']],
      ['ingestion/presentation/dto/response/ImportResponse.java', ['ImportResponse', 'Lines']],
      ['ingestion/presentation/dto/response/ImportRecordResponse.java', ['ImportRecordResponse', 'Issue']],
      ['ingestion/presentation/dto/response/ImportPageResponses.java', ['ImportPageResponse', 'ImportRecordPageResponse']],
    ]
    for (const [path, names] of files) {
      const records = javaRecords(path)
      for (const name of names) expect(sorted(records[name] ?? []), name).toEqual(sorted(RECORD_FIELDS[name]))
    }
    // O record Java se chama Period (aninhado em ImportResponse); no contrato, ImportPeriod.
    expect(sorted(javaRecords('ingestion/presentation/dto/response/ImportResponse.java').Period ?? [])).toEqual(
      sorted(RECORD_FIELDS.ImportPeriod),
    )
    // Idem para Statement (ImportStatement) e Installment (ImportInstallment).
    expect(sorted(javaRecords('ingestion/presentation/dto/response/ImportResponse.java').Statement ?? [])).toEqual(
      sorted(RECORD_FIELDS.ImportStatement),
    )
    expect(sorted(javaRecords('ingestion/presentation/dto/response/ImportRecordResponse.java').Installment ?? []))
      .toEqual(sorted(RECORD_FIELDS.ImportInstallment))
  })

  it('requests de Accounts têm exatamente os campos do contrato manual', () => {
    for (const name of ['CreateAccountRequest', 'UpdateAccountRequest'] as const) {
      const records = javaRecords(`accounts/presentation/dto/request/${name}.java`)
      expect(sorted(records[name] ?? []), name).toEqual(sorted(RECORD_FIELDS[name]))
    }
    // No PUT, includedInTotal é obrigatório (substituição completa); no POST, opcional.
    const notNull = /@NotNull\s+Boolean\s+includedInTotal/
    expect(read('accounts/presentation/dto/request/UpdateAccountRequest.java')).toMatch(notNull)
    expect(read('accounts/presentation/dto/request/CreateAccountRequest.java')).not.toMatch(notNull)
  })

  it('limites de nome e instituição são os mesmos do domínio', () => {
    const max = (path: string) => Number(/MAX_LENGTH\s*=\s*(\d+)/.exec(read(path))?.[1])
    expect(max('accounts/domain/valueobject/AccountName.java')).toBe(ACCOUNT_NAME_MAX_LENGTH)
    expect(max('accounts/domain/valueobject/InstitutionName.java')).toBe(INSTITUTION_NAME_MAX_LENGTH)
  })

  it('records e requests de Transactions têm exatamente os campos do contrato manual', () => {
    const files: [string, (keyof typeof RECORD_FIELDS)[]][] = [
      ['transactions/presentation/dto/response/TransactionResponse.java', ['TransactionResponse']],
      ['transactions/presentation/dto/response/TransactionPageResponse.java', ['TransactionPageResponse']],
      ['transactions/presentation/dto/request/CreateTransactionRequest.java', ['CreateTransactionRequest']],
      ['transactions/presentation/dto/request/UpdateTransactionRequest.java', ['UpdateTransactionRequest']],
      ['transactions/presentation/dto/request/CategorizeTransactionRequest.java', ['CategorizeTransactionRequest']],
      ['transactions/presentation/dto/request/MarkAsRefundRequest.java', ['MarkAsRefundRequest']],
      ['transactions/presentation/dto/response/CategoryListResponse.java', ['CategoryListResponse', 'CategoryResponse']],
    ]
    for (const [path, names] of files) {
      const records = javaRecords(path)
      for (const name of names) expect(sorted(records[name] ?? []), name).toEqual(sorted(RECORD_FIELDS[name]))
    }
    expect(read('transactions/presentation/dto/response/TransactionResponse.java')).toMatch(/MoneyDto\s+amount\b/)
  })

  it('limites de Transactions (descrição, busca, página, Idempotency-Key) são os do backend', () => {
    const constant = (path: string, name: string) => Number(new RegExp(`${name}\\s*=\\s*(\\d+)`).exec(read(path))?.[1])
    expect(constant('transactions/domain/valueobject/TransactionDescription.java', 'MAX_LENGTH')).toBe(
      TRANSACTION_DESCRIPTION_MAX_LENGTH,
    )
    expect(constant('transactions/application/usecase/ListTransactions.java', 'MAX_TEXT_LENGTH')).toBe(
      TRANSACTION_SEARCH_MAX_LENGTH,
    )
    expect(constant('transactions/application/usecase/ListTransactions.java', 'MAX_PAGE_SIZE')).toBe(TRANSACTION_MAX_PAGE_SIZE)
    expect(constant('transactions/domain/valueobject/IdempotencyKey.java', 'MAX_LENGTH')).toBe(IDEMPOTENCY_KEY_MAX_LENGTH)
    expect(constant('transactions/domain/valueobject/CategoryCode.java', 'MAX_LENGTH')).toBe(CATEGORY_CODE_MAX_LENGTH)
  })

  it('direção da linha importada é só INFLOW/OUTFLOW', () => {
    const source = read('ingestion/presentation/dto/response/ImportRecordResponse.java')
    expect(source).toContain('"INFLOW"')
    expect(source).toContain('"OUTFLOW"')
    expect(sorted(RECORD_DIRECTIONS)).toEqual(['INFLOW', 'OUTFLOW'])
  })

  it.each([
    ['cards/domain/enums/CardBrand.java', CARD_BRANDS],
    ['cards/domain/enums/CardStatus.java', CARD_STATUSES],
    ['cards/domain/enums/InvoiceStatus.java', INVOICE_STATUSES],
    ['cards/domain/enums/InvoiceDatesSource.java', INVOICE_DATES_SOURCES],
    ['cards/domain/enums/InvoiceItemKind.java', INVOICE_ITEM_KINDS],
    ['cards/domain/enums/InstallmentStatus.java', INSTALLMENT_STATUSES],
    ['cards/domain/enums/InstallmentPlanStatus.java', INSTALLMENT_PLAN_STATUSES],
  ])('enum %s', (path, frontend) => {
    expect(sorted(frontend)).toEqual(sorted(javaEnum(path)))
  })

  it('records e request de Cartões têm exatamente os campos do contrato manual', () => {
    const responses = javaRecords('cards/presentation/dto/response/CardResponses.java')
    for (const name of [
      'CardResponse', 'CardListResponse', 'InvoiceSummaryResponse', 'InvoiceListResponse', 'InvoiceResponse',
      'InvoiceItemResponse', 'InstallmentRef', 'InvoiceItemPageResponse', 'FutureInstallmentResponse',
      'FutureInvoiceMonthResponse', 'FutureInvoicesResponse', 'UtilizationCompositionResponse', 'UtilizationResponse',
      'CardSummaryResponse', 'CardsSummaryResponse', 'PurchaseInstallmentResponse', 'CardPurchaseResponse',
      'CardTransactionResponse', 'InstallmentItemResponse', 'ReservedLimitResponse', 'InstallmentPlanResponse',
      'InstallmentPlanListResponse', 'CardPaymentReconciliationResponse', 'CurrentInvoiceShareResponse',
      'UtilizationPointResponse', 'UtilizationHistoryResponse',
    ] as const) {
      expect(sorted(responses[name] ?? []), name).toEqual(sorted(RECORD_FIELDS[name]))
    }
    for (const name of [
      'CardPurchaseRequest', 'CardPaymentRequest', 'CardCreditRequest', 'CardPaymentReconciliationRequest',
    ] as const) {
      const records = javaRecords(`cards/presentation/dto/request/${name}.java`)
      expect(sorted(records[name] ?? []), name).toEqual(sorted(RECORD_FIELDS[name]))
    }
    expect(Number(/MAX_INSTALLMENTS\s*=\s*(\d+)/.exec(read('cards/domain/service/InstallmentSplitter.java'))?.[1]))
      .toBe(MAX_INSTALLMENTS)
    // O último componente do request (campos desconhecidos → 400) não faz parte do contrato.
    const request = (javaRecords('cards/presentation/dto/request/CardRequest.java').CardRequest ?? []).filter(
      (field) => /^\w+$/.test(field) && field !== 'unknownFields',
    )
    expect(sorted(request)).toEqual(sorted(RECORD_FIELDS.CardRequest))
    expect(read('cards/presentation/dto/request/CardRequest.java')).toMatch(/@JsonAnySetter/)
    expect(Number(/MAX_LENGTH\s*=\s*(\d+)/.exec(read('cards/domain/valueobject/CardNickname.java'))?.[1])).toBe(
      CARD_NICKNAME_MAX_LENGTH,
    )
  })

  it('MoneyDto continua {amount: String, currency: String}', () => {
    const source = read('shared/presentation/money/MoneyDto.java')
    expect(source).toMatch(/record\s+MoneyDto\s*\(\s*@NotBlank\s+String\s+amount\s*,\s*@NotBlank\s+String\s+currency\s*\)/)
  })

  it('dinheiro do Overview trafega como MoneyDto (string), nunca como número', () => {
    const source = read('overview/presentation/dto/response/OverviewResponse.java')
    for (const field of ['totalBalance', 'income', 'expense', 'refunds', 'net', 'volume', 'balance', 'amount']) {
      expect(source, field).toMatch(new RegExp(`MoneyDto\\s+${field}\\b`))
    }
  })
})

describe.skipIf(!hasBackend)('contrato de Objetivos × backend (drift)', () => {
  it.each([
    ['goals/domain/enums/GoalType.java', GOAL_TYPES],
    ['goals/domain/enums/GoalStatus.java', GOAL_STATUSES],
    ['goals/domain/enums/GoalOriginKind.java', GOAL_ORIGIN_KINDS],
  ])('enum %s', (path, frontend) => {
    expect(sorted(frontend)).toEqual(sorted(javaEnum(path)))
  })

  it('limites do nome e dos cenários são os do backend', () => {
    expect(read('goals/domain/valueobject/GoalName.java')).toContain(`MAX_LENGTH = ${GOAL_NAME_MAX_LENGTH};`)
    expect(read('goals/application/service/GoalSimulator.java')).toContain(`MAX_SCENARIOS = ${GOAL_MAX_SCENARIOS};`)
  })
})

describe.skipIf(!hasBackend)('contrato de Consentimentos × backend (drift)', () => {
  it('estados são os do backend', () => {
    expect(sorted(CONSENT_STATES)).toEqual(sorted(javaEnum('consents/domain/enums/ConsentState.java')))
  })

  it('finalidades e versões vigentes dos termos são as do backend', () => {
    const source = read('consents/domain/enums/ConsentPurpose.java')
    const declared = [...source.matchAll(/^\s{4}([A-Z][A-Z0-9_]*)\(/gm)].map((match) => match[1] ?? '')
    expect(sorted(CONSENT_PURPOSES)).toEqual(sorted(declared))
    for (const purpose of CONSENT_PURPOSES) {
      expect(source).toMatch(new RegExp(`${purpose}\\("${CONSENT_TERMS_VERSIONS[purpose]}"`))
    }
  })
})

/** Remove anotações Java com parênteses balanceados, respeitando strings (descrições com "(" e ")"). */
function withoutAnnotations(source: string): string {
  let out = ''
  for (let i = 0; i < source.length; i++) {
    if (source[i] !== '@' || !/\w/.test(source[i + 1] ?? '')) {
      out += source[i]
      continue
    }
    i++
    while (/[\w.]/.test(source[i] ?? '')) i++
    if (source[i] === '(') {
      let depth = 0
      let inString = false
      for (; i < source.length; i++) {
        const c = source[i]
        if (inString) {
          if (c === '\\') i++
          else if (c === '"') inString = false
        } else if (c === '"') inString = true
        else if (c === '(') depth++
        else if (c === ')' && --depth === 0) break
      }
    } else i--
  }
  return out
}

function copilotRecords(path: string): Record<string, string[]> {
  const records: Record<string, string[]> = {}
  for (const [, name, params] of withoutAnnotations(read(path)).matchAll(/record\s+(\w+)\s*\(([^)]*)\)/g)) {
    // Vírgulas dentro de genéricos (`Map<String, String>`) não separam componentes.
    const flat = params!.replace(/<[^<>]*(?:<[^<>]*>[^<>]*)*>/g, (generic) => generic.replace(/[,\s]/g, ''))
    records[name!] = flat.split(',').map((p) => p.trim().split(/\s+/).pop()!).filter(Boolean)
  }
  return records
}

describe.skipIf(!hasBackend)('contrato do Copilot × backend (drift)', () => {
  it.each([
    ['copilot/domain/enums/Intent.java', COPILOT_INTENTS],
    ['copilot/domain/enums/Notice.java', COPILOT_NOTICES],
    ['copilot/domain/enums/ActionKind.java', COPILOT_ACTION_KINDS],
    ['copilot/domain/enums/ValueKind.java', COPILOT_VALUE_KINDS],
    ['copilot/domain/enums/ValidationStatus.java', COPILOT_VALIDATIONS],
    ['copilot/domain/enums/FeedbackRating.java', COPILOT_FEEDBACK_RATINGS],
    ['copilot/domain/enums/FeedbackReason.java', COPILOT_FEEDBACK_REASONS],
  ])('enum %s', (path, frontend) => {
    expect(sorted(frontend)).toEqual(sorted(javaEnum(path).map((c) => c.replace(/\(.*$/s, ''))))
  })

  it('confiança e tipo da resposta são os enums em minúsculas', () => {
    expect(sorted(COPILOT_CONFIDENCES)).toEqual(
      sorted(javaEnum('copilot/domain/enums/AnswerConfidence.java').map((c) => c.toLowerCase())),
    )
    expect(sorted(COPILOT_ANSWER_TYPES)).toEqual(
      sorted(javaEnum('copilot/domain/enums/AnswerType.java').map((c) => c.toLowerCase())),
    )
  })

  it('códigos da resposta são ResultCode sem as falhas que viram erro HTTP', () => {
    const answered = javaEnum('copilot/domain/enums/ResultCode.java').filter(
      (c) => !['CONSENT_REQUIRED', 'RATE_LIMITED', 'QUOTA_EXCEEDED', 'PROVIDER_UNAVAILABLE'].includes(c),
    )
    expect(sorted(COPILOT_ANSWER_CODES)).toEqual(sorted(answered))
  })

  it('records de resposta e de pedido têm exatamente os campos do contrato manual', () => {
    const responses = copilotRecords('copilot/presentation/dto/response/CopilotResponses.java')
    const pairs: [string, keyof typeof RECORD_FIELDS][] = [
      ['CopilotAnswerResponse', 'CopilotAnswer'],
      ['Answer', 'CopilotAnswerBody'],
      ['Segment', 'CopilotSegment'],
      ['Value', 'CopilotValue'],
      ['BreakdownBody', 'CopilotBreakdown'],
      ['BreakdownRow', 'CopilotBreakdownRow'],
      ['Action', 'CopilotAction'],
      ['DataUsed', 'CopilotDataUsed'],
      ['UsageResponse', 'CopilotUsage'],
      ['UsageWindow', 'CopilotUsageWindow'],
      ['DataScope', 'CopilotDataScope'],
      ['ConversationItem', 'CopilotConversationSummary'],
      ['ConversationPageResponse', 'CopilotConversationPage'],
      ['MessageResponse', 'CopilotConversationMessage'],
      ['ConversationResponse', 'CopilotConversation'],
    ]
    for (const [java, contract] of pairs) {
      expect(sorted(responses[java] ?? []), contract).toEqual(sorted(RECORD_FIELDS[contract]))
    }
    const ask = copilotRecords('copilot/presentation/dto/request/AskQuestionRequest.java')
    expect(sorted(ask.AskQuestionRequest ?? [])).toEqual(sorted(RECORD_FIELDS.CopilotAskQuestionRequest))
    expect(sorted(ask.Context ?? [])).toEqual(sorted(RECORD_FIELDS.CopilotEntryContext))
    const feedback = copilotRecords('copilot/presentation/dto/request/FeedbackRequest.java')
    expect(sorted(feedback.FeedbackRequest ?? [])).toEqual(sorted(RECORD_FIELDS.CopilotFeedbackRequest))
  })

  it('tipos de contexto de entrada e de segmento são os do backend', () => {
    const ask = read('copilot/presentation/dto/request/AskQuestionRequest.java')
    for (const kind of COPILOT_CONTEXT_KINDS) expect(ask, kind).toContain(`"${kind}"`)
    const responses = read('copilot/presentation/dto/response/CopilotResponses.java')
    for (const kind of COPILOT_SEGMENT_KINDS) expect(responses, kind).toContain(`"${kind}"`)
  })

  it('limites da pergunta são os do backend', () => {
    expect(read('copilot/application/usecase/AskCopilot.java')).toContain(
      `MAX_PREVIOUS_QUESTIONS = ${COPILOT_MAX_PREVIOUS_QUESTIONS};`,
    )
    expect(read('copilot/domain/model/PipelineSettings.java')).toMatch(
      new RegExp(`12_000,\\s*${COPILOT_QUESTION_MAX_LENGTH}\\)`),
    )
  })
})
