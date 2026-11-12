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

/** Eventos da fatura nos "Próximos eventos" (ADR-0006 §18). */
export const UPCOMING_INVOICE_EVENT_KINDS = ['INVOICE_CLOSING', 'INVOICE_DUE'] as const
export const UPCOMING_INVOICE_EVENT_STATUSES = ['PARTIAL', 'CLOSED', 'OVERDUE'] as const
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
export const RECORD_STATUSES = ['VALID', 'INVALID', 'DUPLICATE', 'SKIPPED', 'IMPORTED', 'RECONCILED'] as const
/** Direção de uma linha importada (ImportRecordResponse): só entrada ou saída, nunca transferência. */
export const RECORD_DIRECTIONS = ['INFLOW', 'OUTFLOW'] as const
/** Natureza da categoria (ADR-0012 §2): EXPENSE serve a despesa e reembolso; INCOME, a receita. */
export const CATEGORY_NATURES = ['EXPENSE', 'INCOME'] as const
export const CARD_BRANDS = ['VISA', 'MASTERCARD', 'ELO', 'AMEX', 'HIPERCARD', 'OTHER'] as const
export const CARD_STATUSES = ['ACTIVE', 'ARCHIVED'] as const
export const INVOICE_STATUSES = ['FUTURE', 'OPEN', 'CLOSED', 'PARTIALLY_PAID', 'PAID', 'OVERDUE'] as const
export const INVOICE_DATES_SOURCES = ['CALCULATED', 'IMPORTED'] as const
export const INVOICE_ITEM_KINDS = ['CHARGE', 'CREDIT'] as const
export const INSTALLMENT_STATUSES = ['PAID', 'CURRENT', 'FUTURE', 'CANCELLED', 'BEFORE_TRACKING'] as const
export const INSTALLMENT_PLAN_STATUSES = ['ACTIVE', 'FINISHED', 'CANCELLED'] as const
/** Parcelas de uma compra no cartão (InstallmentSplitter.MAX_INSTALLMENTS). */
export const MAX_INSTALLMENTS = 48
export const CARD_NICKNAME_MAX_LENGTH = 100
/** Objetivos (ADR-0034 §1, ADR-0035): tipos, status, ações e o limite do nome (`GoalName.MAX_LENGTH`). */
export const GOAL_TYPES = ['EMERGENCY_RESERVE', 'PURCHASE', 'CUSTOM'] as const
export const GOAL_STATUSES = ['ACTIVE', 'PAUSED', 'COMPLETED', 'ARCHIVED'] as const
export const GOAL_ORIGIN_KINDS = ['MANUAL', 'INSIGHT', 'HEALTH'] as const
export const GOAL_NAME_MAX_LENGTH = 60
/** `GoalSimulator.MAX_SCENARIOS` do backend. */
export const GOAL_MAX_SCENARIOS = 5
/** Consentimentos (ADR-0038 §2–§3): finalidades, estados e a versão vigente do termo de cada finalidade. */
export const CONSENT_PURPOSES = ['COPILOT_DATA_USE', 'COPILOT_HISTORY'] as const
export const CONSENT_STATES = ['PENDING', 'ACTIVE', 'EXPIRED', 'REVOKED', 'FAILED'] as const
export const CONSENT_TERMS_VERSIONS = {
  COPILOT_DATA_USE: 'copilot-data-v1',
  COPILOT_HISTORY: 'copilot-history-v1',
} as const satisfies Record<(typeof CONSENT_PURPOSES)[number], string>

/** `AccountName.MAX_LENGTH` e `InstitutionName.MAX_LENGTH` do backend: o BFF e o formulário recusam antes. */
export const ACCOUNT_NAME_MAX_LENGTH = 100
export const INSTITUTION_NAME_MAX_LENGTH = 100
/** `TransactionDescription.MAX_LENGTH` do backend. */
export const TRANSACTION_DESCRIPTION_MAX_LENGTH = 200
/** `ListTransactions.MAX_TEXT_LENGTH` (busca) e `MAX_PAGE_SIZE` do backend. */
export const TRANSACTION_SEARCH_MAX_LENGTH = 100
export const TRANSACTION_MAX_PAGE_SIZE = 100
/** Copilot (ADR-0039, ADR-0040): enums do pedido e da resposta, e os limites da pergunta. */
export const COPILOT_ANSWER_CODES = ['OK', 'SAFE_MODE', 'INSUFFICIENT_DATA', 'OUT_OF_SCOPE'] as const
export const COPILOT_INTENTS = [
  'OVERVIEW', 'CASH_FLOW', 'CATEGORY', 'MERCHANT', 'HEALTH', 'INSIGHTS', 'CARDS', 'FORECAST', 'GOALS',
  'GOAL_SIMULATION', 'OUT_OF_SCOPE', 'UNSUPPORTED_ACTION',
] as const
export const COPILOT_CONFIDENCES = ['high', 'medium', 'low'] as const
export const COPILOT_VALIDATIONS = ['VALID', 'REGENERATED', 'SAFE_MODE', 'NOT_APPLICABLE'] as const
export const COPILOT_NOTICES = ['PARTIAL_PERIOD', 'ESTIMATED', 'SIMULATED', 'SAFE_MODE', 'UPDATING'] as const
export const COPILOT_ANSWER_TYPES = [
  'analysis', 'explanation', 'comparison', 'simulation', 'out_of_scope', 'insufficient_data',
] as const
export const COPILOT_SEGMENT_KINDS = ['TEXT', 'VALUE'] as const
export const COPILOT_VALUE_KINDS = ['FACT', 'ESTIMATED', 'SIMULATED'] as const
export const COPILOT_ACTION_KINDS = [
  'OPEN_INVOICE', 'OPEN_INSTALLMENTS', 'OPEN_HEALTH', 'OPEN_INSIGHT', 'OPEN_GOAL', 'OPEN_ANALYSIS_CATEGORY',
  'OPEN_FORECAST',
] as const
export const COPILOT_CONTEXT_KINDS = ['INSIGHT', 'INVOICE', 'INSTALLMENT_PLAN', 'GOAL'] as const
export const COPILOT_FEEDBACK_RATINGS = ['HELPFUL', 'NOT_HELPFUL'] as const
export const COPILOT_FEEDBACK_REASONS = ['WRONG_NUMBERS', 'DID_NOT_ANSWER', 'CONFUSING', 'OTHER'] as const
/** `PipelineSettings.maxQuestionChars`: pergunta de 1 a 500 caracteres depois do strip. */
export const COPILOT_QUESTION_MAX_LENGTH = 500
/** `AskCopilot.MAX_PREVIOUS_QUESTIONS`. */
export const COPILOT_MAX_PREVIOUS_QUESTIONS = 3

/** `IdempotencyKey.MAX_LENGTH` do backend (só caracteres não reservados de URI + ':'). */
export const IDEMPOTENCY_KEY_MAX_LENGTH = 100
/** `CategoryCode.MAX_LENGTH` do backend (maiúsculas, dígitos e `_`). */
export const CATEGORY_CODE_MAX_LENGTH = 40

type Tx = O['recentTransactions'][number]
type Txn = S['TransactionResponse']
type CreateTxn = S['CreateTransactionRequest']
type Acc = S['AccountResponse']
type CreateAcc = S['CreateAccountRequest']
type UpdateAcc = S['UpdateAccountRequest']
type Imp = S['ImportResponse']
type Rec = S['ImportRecordResponse']
type Cat = S['CategoryResponse']
type Card = S['CardResponse']
type Inv = S['InvoiceSummaryResponse']
export type EnumChecks = [
  Assert<Equals<Values<typeof OVERVIEW_STATES>, O['state']>>,
  Assert<Equals<Values<typeof UPCOMING_INVOICE_EVENT_KINDS>, S['UpcomingInvoiceEvent']['kind']>>,
  Assert<Equals<Values<typeof UPCOMING_INVOICE_EVENT_STATUSES>, S['UpcomingInvoiceEvent']['status']>>,
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
  Assert<Equals<Values<typeof TRANSACTION_TYPES>, Txn['type']>>,
  Assert<Equals<Values<typeof TRANSACTION_STATUSES>, Txn['status']>>,
  Assert<Equals<Values<typeof ADJUSTMENT_DIRECTIONS>, NonNullable<Txn['adjustmentDirection']>>>,
  Assert<Equals<Values<typeof TRANSACTION_SOURCES>, Txn['source']>>,
  Assert<Equals<Values<typeof TRANSACTION_TYPES>, CreateTxn['type']>>,
  Assert<Equals<Values<typeof CATEGORY_NATURES>, Cat['nature']>>,
  Assert<Equals<Values<typeof CARD_BRANDS>, Card['brand']>>,
  Assert<Equals<Values<typeof CARD_BRANDS>, S['CardRequest']['brand']>>,
  Assert<Equals<Values<typeof CARD_STATUSES>, Card['status']>>,
  Assert<Equals<Values<typeof INVOICE_STATUSES>, Inv['status']>>,
  Assert<Equals<Values<typeof INVOICE_DATES_SOURCES>, Inv['datesSource']>>,
  Assert<Equals<Values<typeof INVOICE_ITEM_KINDS>, S['InvoiceItemResponse']['kind']>>,
  Assert<Equals<Values<typeof INSTALLMENT_STATUSES>, S['InstallmentItemResponse']['status']>>,
  Assert<Equals<Values<typeof INSTALLMENT_PLAN_STATUSES>, S['InstallmentPlanResponse']['status']>>,
  Assert<Equals<Values<typeof GOAL_TYPES>, S['GoalResponse']['type']>>,
  Assert<Equals<Values<typeof GOAL_TYPES>, S['CreateGoalRequest']['type']>>,
  Assert<Equals<Values<typeof GOAL_STATUSES>, S['GoalResponse']['status']>>,
  Assert<Equals<Values<typeof GOAL_ORIGIN_KINDS>, S['GoalOriginRequest']['kind']>>,
  Assert<Equals<Values<typeof CONSENT_PURPOSES>, S['ConsentResponse']['purpose']>>,
  Assert<Equals<Values<typeof CONSENT_STATES>, S['ConsentResponse']['state']>>,
  Assert<Equals<Values<typeof COPILOT_ANSWER_CODES>, S['CopilotAnswer']['code']>>,
  Assert<Equals<Values<typeof COPILOT_INTENTS>, NonNullable<S['CopilotAnswer']['intent']>>>,
  Assert<Equals<Values<typeof COPILOT_CONFIDENCES>, NonNullable<S['CopilotAnswer']['confidence']>>>,
  Assert<Equals<Values<typeof COPILOT_VALIDATIONS>, S['CopilotAnswer']['validation']>>,
  Assert<Equals<Values<typeof COPILOT_NOTICES>, S['CopilotAnswer']['notices'][number]>>,
  Assert<Equals<Values<typeof COPILOT_ANSWER_TYPES>, S['CopilotAnswerBody']['type']>>,
  Assert<Equals<Values<typeof COPILOT_SEGMENT_KINDS>, S['CopilotSegment']['kind']>>,
  Assert<Equals<Values<typeof COPILOT_VALUE_KINDS>, S['CopilotValue']['kind']>>,
  Assert<Equals<Values<typeof COPILOT_ACTION_KINDS>, S['CopilotAction']['kind']>>,
  Assert<Equals<Values<typeof COPILOT_CONTEXT_KINDS>, NonNullable<S['CopilotEntryContext']>['kind']>>,
  Assert<Equals<Values<typeof COPILOT_FEEDBACK_RATINGS>, S['CopilotFeedbackRequest']['rating']>>,
  Assert<Equals<Values<typeof COPILOT_FEEDBACK_REASONS>, NonNullable<S['CopilotFeedbackRequest']['reason']>>>,
]

/** Campos de cada record Java (nomes iguais aos do backend) ↔ chaves do tipo TS correspondente. */
export const RECORD_FIELDS = {
  OverviewResponse: [
    'workspaceId', 'period', 'state', 'summary', 'cashFlow', 'accounts', 'recentTransactions', 'cards',
  ],
  InvoicesToPay: ['activeCards', 'openInvoices', 'nextDue', 'upcomingEvents', 'asOf', 'calculationVersion'],
  InvoicesNextDue: ['date', 'amount'],
  UpcomingInvoiceEvent: ['date', 'kind', 'status', 'dueMonth', 'amount', 'card'],
  Period: ['type', 'from', 'to'],
  Summary: ['totalBalance', 'balanceAsOf', 'accountCount', 'pendingTransactions'],
  CashFlow: ['income', 'expense', 'refunds', 'net', 'transfers'],
  Transfers: ['count', 'volume'],
  AccountSummary: ['id', 'name', 'type', 'institutionName', 'includedInTotal', 'balance', 'movementCount'],
  AccountRef: ['id', 'name'],
  RecentTransaction: [
    'id', 'type', 'flow', 'adjustmentDirection', 'status', 'amount', 'occurredOn', 'description', 'account',
    'destinationAccount', 'refundOfTransactionId', 'source', 'card',
  ],
  CardRef: ['id', 'nickname', 'last4'],
  MoneyDto: ['amount', 'currency'],
  AccountResponse: [
    'id', 'workspaceId', 'name', 'type', 'institutionName', 'currency', 'includedInTotal', 'status', 'createdAt',
    'updatedAt',
  ],
  AccountListResponse: ['items'],
  CreateAccountRequest: ['name', 'type', 'institutionName', 'includedInTotal'],
  UpdateAccountRequest: ['name', 'type', 'institutionName', 'includedInTotal'],
  ImportResponse: [
    'id', 'workspaceId', 'accountId', 'cardId', 'format', 'status', 'fileName', 'fileSize', 'lines', 'period',
    'statement', 'sameFileImportedBefore', 'failureReason', 'createdAt', 'previewExpiresAt', 'confirmedAt', 'completedAt',
  ],
  Lines: ['total', 'valid', 'invalid', 'duplicate', 'skipped', 'imported', 'reconciled', 'categorized'],
  ImportPeriod: ['from', 'to'],
  ImportStatement: ['dueDate', 'minimumPayment'],
  ImportRecordResponse: [
    'lineNumber', 'status', 'occurredOn', 'amount', 'direction', 'description', 'issue', 'transactionId', 'skipReason',
    'installment',
  ],
  Issue: ['field', 'code'],
  ImportInstallment: ['number', 'count'],
  ImportPageResponse: ['items', 'page', 'pageSize', 'totalItems'],
  ImportRecordPageResponse: ['items', 'page', 'pageSize', 'totalItems'],
  TransactionResponse: [
    'id', 'workspaceId', 'accountId', 'destinationAccountId', 'cardId', 'destinationCardId', 'type',
    'adjustmentDirection', 'amount', 'occurredOn', 'description', 'status', 'source', 'refundOfTransactionId', 'categoryCode', 'categorySource', 'merchantName',
    'reclassifiedFromTransactionId', 'reclassifiedAsTransactionId', 'createdAt', 'updatedAt',
  ],
  TransactionPageResponse: ['items', 'page', 'pageSize', 'totalItems'],
  CreateTransactionRequest: [
    'type', 'accountId', 'destinationAccountId', 'adjustmentDirection', 'amount', 'occurredOn', 'description', 'status',
    'refundOfTransactionId',
  ],
  UpdateTransactionRequest: ['description'],
  CategorizeTransactionRequest: ['categoryCode', 'applyToSimilar'],
  MarkAsRefundRequest: ['refundOfTransactionId'],
  CategoryListResponse: ['items'],
  CategoryResponse: ['code', 'name', 'nature', 'active'],
  CardRequest: [
    'nickname', 'institutionName', 'brand', 'last4', 'limit', 'closingDay', 'dueDay', 'defaultPaymentAccountId',
  ],
  CardResponse: [
    'id', 'workspaceId', 'nickname', 'institutionName', 'brand', 'last4', 'limit', 'closingDay', 'dueDay',
    'defaultPaymentAccountId', 'status', 'trackingStartDueMonth', 'createdAt', 'updatedAt',
  ],
  CardListResponse: ['items'],
  InvoiceSummaryResponse: [
    'dueMonth', 'periodStart', 'closingDate', 'dueDate', 'effectiveDueDate', 'datesSource', 'status', 'charges',
    'credits', 'amount', 'paid', 'remaining', 'minimumPayment', 'partial',
  ],
  InvoiceListResponse: ['items'],
  InvoiceResponse: ['invoice', 'itemCount', 'asOf', 'calculationVersion'],
  InvoiceItemResponse: [
    'kind', 'transactionId', 'occurredOn', 'description', 'categoryCode', 'merchantName', 'source', 'installment',
    'amount', 'cancelledAmount',
  ],
  InstallmentRef: ['number', 'count'],
  InvoiceItemPageResponse: ['items', 'page', 'pageSize', 'totalItems'],
  FutureInstallmentResponse: ['transactionId', 'description', 'number', 'count', 'amount'],
  FutureInvoiceMonthResponse: ['dueMonth', 'committed', 'installments'],
  FutureInvoicesResponse: ['months', 'totalCommitted', 'asOf', 'calculationVersion'],
  UtilizationCompositionResponse: ['previousOutstanding', 'currentInvoice', 'futureInstallments'],
  UtilizationResponse: [
    'limit', 'utilized', 'available', 'credit', 'utilizationPercent', 'utilizationWithoutFuturePercent',
    'composition', 'asOf', 'calculationVersion',
  ],
  CardSummaryResponse: ['card', 'currentInvoice', 'utilization', 'nextInvoiceCommitted'],
  CardPurchaseRequest: ['amount', 'occurredOn', 'description', 'installmentCount'],
  CardPaymentRequest: ['accountId', 'amount', 'occurredOn', 'description'],
  CardCreditRequest: ['amount', 'occurredOn', 'description', 'refundOfTransactionId'],
  PurchaseInstallmentResponse: ['number', 'amount', 'dueMonth'],
  CardPurchaseResponse: ['transaction', 'installments', 'calculationVersion'],
  CardTransactionResponse: ['transaction'],
  CardPaymentReconciliationResponse: ['payment', 'matchedExisting'],
  CardPaymentReconciliationRequest: ['transactionId'],
  InstallmentItemResponse: ['number', 'dueMonth', 'amount', 'cancelledAmount', 'status'],
  ReservedLimitResponse: ['dueMonth', 'amount'],
  CurrentInvoiceShareResponse: ['dueMonth', 'installment', 'invoiceAmount', 'percent'],
  UtilizationPointResponse: ['date', 'utilized', 'limit', 'utilizationPercent', 'current'],
  UtilizationHistoryResponse: ['points', 'asOf', 'calculationVersion'],
  InstallmentPlanResponse: [
    'transactionId', 'description', 'purchasedOn', 'total', 'count', 'installmentAmount', 'paidCount', 'paidAmount',
    'currentNumber', 'remainingAfterCurrent', 'lastDueMonth', 'status', 'installments', 'reservedLimit',
    'currentInvoiceShare',
  ],
  InstallmentPlanListResponse: ['items'],
  CardsSummaryResponse: [
    'totalLimit', 'utilized', 'openInvoices', 'nextDueDate', 'cards', 'asOf', 'calculationVersion',
  ],
  CopilotAskQuestionRequest: ['question', 'conversationId', 'previousQuestions', 'context'],
  CopilotEntryContext: ['kind', 'insightId', 'cardId', 'month', 'planId', 'goalId'],
  CopilotAnswer: [
    'requestId', 'conversationId', 'messageId', 'code', 'intent', 'question', 'answer', 'confidence', 'validation',
    'notices', 'versions', 'createdAt',
  ],
  CopilotAnswerBody: ['type', 'segments', 'explanation', 'values', 'breakdown', 'actions', 'dataUsed'],
  CopilotSegment: ['kind', 'text', 'ref'],
  CopilotValue: ['ref', 'label', 'formatted', 'kind', 'period', 'source'],
  CopilotBreakdown: ['title', 'rows', 'totalRef'],
  CopilotBreakdownRow: ['label', 'valueRef', 'detail'],
  CopilotAction: ['kind', 'label', 'targetId', 'targetName', 'params'],
  CopilotDataUsed: ['label', 'partial'],
  CopilotUsage: ['month', 'day', 'dataScope', 'provisional'],
  CopilotUsageWindow: ['used', 'limit', 'resetsAt'],
  CopilotDataScope: ['from', 'to'],
  CopilotConversationSummary: ['id', 'title', 'createdAt', 'lastMessageAt', 'messageCount'],
  CopilotConversationPage: ['historyEnabled', 'items', 'nextCursor'],
  CopilotConversationMessage: ['id', 'requestId', 'question', 'createdAt', 'answer'],
  CopilotConversation: ['id', 'title', 'createdAt', 'messages'],
  CopilotFeedbackRequest: ['rating', 'reason'],
} as const

type F = typeof RECORD_FIELDS
export type FieldChecks = [
  Assert<Equals<Values<F['OverviewResponse']>, keyof O>>,
  Assert<Equals<Values<F['InvoicesToPay']>, keyof S['InvoicesToPay']>>,
  Assert<Equals<Values<F['InvoicesNextDue']>, keyof S['InvoicesNextDue']>>,
  Assert<Equals<Values<F['UpcomingInvoiceEvent']>, keyof S['UpcomingInvoiceEvent']>>,
  Assert<Equals<Values<F['Period']>, keyof O['period']>>,
  Assert<Equals<Values<F['Summary']>, keyof O['summary']>>,
  Assert<Equals<Values<F['CashFlow']>, keyof NonNullable<O['cashFlow']>>>,
  Assert<Equals<Values<F['Transfers']>, keyof NonNullable<O['cashFlow']>['transfers']>>,
  Assert<Equals<Values<F['AccountSummary']>, keyof O['accounts'][number]>>,
  Assert<Equals<Values<F['AccountRef']>, keyof NonNullable<Tx['account']>>>,
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
  Assert<Equals<Values<F['TransactionResponse']>, keyof Txn>>,
  Assert<Equals<Values<F['TransactionPageResponse']>, keyof S['TransactionPageResponse']>>,
  Assert<Equals<Values<F['CreateTransactionRequest']>, keyof CreateTxn>>,
  Assert<Equals<Values<F['UpdateTransactionRequest']>, keyof S['UpdateTransactionRequest']>>,
  Assert<Equals<Values<F['CategorizeTransactionRequest']>, keyof S['CategorizeTransactionRequest']>>,
  Assert<Equals<Values<F['MarkAsRefundRequest']>, keyof S['MarkAsRefundRequest']>>,
  Assert<Equals<Values<F['CategoryListResponse']>, keyof S['CategoryListResponse']>>,
  Assert<Equals<Values<F['CategoryResponse']>, keyof Cat>>,
  Assert<Equals<Values<F['CardRequest']>, keyof S['CardRequest']>>,
  Assert<Equals<Values<F['CardResponse']>, keyof Card>>,
  Assert<Equals<Values<F['CardListResponse']>, keyof S['CardListResponse']>>,
  Assert<Equals<Values<F['InvoiceSummaryResponse']>, keyof Inv>>,
  Assert<Equals<Values<F['InvoiceListResponse']>, keyof S['InvoiceListResponse']>>,
  Assert<Equals<Values<F['InvoiceResponse']>, keyof S['InvoiceResponse']>>,
  Assert<Equals<Values<F['InvoiceItemResponse']>, keyof S['InvoiceItemResponse']>>,
  Assert<Equals<Values<F['InstallmentRef']>, keyof S['InstallmentRef']>>,
  Assert<Equals<Values<F['InvoiceItemPageResponse']>, keyof S['InvoiceItemPageResponse']>>,
  Assert<Equals<Values<F['FutureInstallmentResponse']>, keyof S['FutureInstallmentResponse']>>,
  Assert<Equals<Values<F['FutureInvoiceMonthResponse']>, keyof S['FutureInvoiceMonthResponse']>>,
  Assert<Equals<Values<F['FutureInvoicesResponse']>, keyof S['FutureInvoicesResponse']>>,
  Assert<Equals<Values<F['UtilizationCompositionResponse']>, keyof S['UtilizationCompositionResponse']>>,
  Assert<Equals<Values<F['UtilizationResponse']>, keyof S['UtilizationResponse']>>,
  Assert<Equals<Values<F['CardSummaryResponse']>, keyof S['CardSummaryResponse']>>,
  Assert<Equals<Values<F['CardsSummaryResponse']>, keyof S['CardsSummaryResponse']>>,
  Assert<Equals<Values<F['CardRef']>, keyof NonNullable<Tx['card']>>>,
  Assert<Equals<Values<F['CardPurchaseRequest']>, keyof S['CardPurchaseRequest']>>,
  Assert<Equals<Values<F['CardPaymentRequest']>, keyof S['CardPaymentRequest']>>,
  Assert<Equals<Values<F['CardCreditRequest']>, keyof S['CardCreditRequest']>>,
  Assert<Equals<Values<F['PurchaseInstallmentResponse']>, keyof S['PurchaseInstallmentResponse']>>,
  Assert<Equals<Values<F['CardPurchaseResponse']>, keyof S['CardPurchaseResponse']>>,
  Assert<Equals<Values<F['CardTransactionResponse']>, keyof S['CardTransactionResponse']>>,
  Assert<Equals<Values<F['CardPaymentReconciliationResponse']>, keyof S['CardPaymentReconciliationResponse']>>,
  Assert<Equals<Values<F['CardPaymentReconciliationRequest']>, keyof S['CardPaymentReconciliationRequest']>>,
  Assert<Equals<Values<F['ImportStatement']>, keyof NonNullable<Imp['statement']>>>,
  Assert<Equals<Values<F['ImportInstallment']>, keyof NonNullable<Rec['installment']>>>,
  Assert<Equals<Values<F['InstallmentItemResponse']>, keyof S['InstallmentItemResponse']>>,
  Assert<Equals<Values<F['ReservedLimitResponse']>, keyof S['ReservedLimitResponse']>>,
  Assert<Equals<Values<F['CurrentInvoiceShareResponse']>, keyof S['CurrentInvoiceShareResponse']>>,
  Assert<Equals<Values<F['UtilizationPointResponse']>, keyof S['UtilizationPointResponse']>>,
  Assert<Equals<Values<F['UtilizationHistoryResponse']>, keyof S['UtilizationHistoryResponse']>>,
  Assert<Equals<Values<F['InstallmentPlanResponse']>, keyof S['InstallmentPlanResponse']>>,
  Assert<Equals<Values<F['InstallmentPlanListResponse']>, keyof S['InstallmentPlanListResponse']>>,
  Assert<Equals<Values<F['CopilotAskQuestionRequest']>, keyof S['CopilotAskQuestionRequest']>>,
  Assert<Equals<Values<F['CopilotEntryContext']>, keyof S['CopilotEntryContext']>>,
  Assert<Equals<Values<F['CopilotAnswer']>, keyof S['CopilotAnswer']>>,
  Assert<Equals<Values<F['CopilotAnswerBody']>, keyof S['CopilotAnswerBody']>>,
  Assert<Equals<Values<F['CopilotSegment']>, keyof S['CopilotSegment']>>,
  Assert<Equals<Values<F['CopilotValue']>, keyof S['CopilotValue']>>,
  Assert<Equals<Values<F['CopilotBreakdown']>, keyof S['CopilotBreakdown']>>,
  Assert<Equals<Values<F['CopilotBreakdownRow']>, keyof S['CopilotBreakdownRow']>>,
  Assert<Equals<Values<F['CopilotAction']>, keyof S['CopilotAction']>>,
  Assert<Equals<Values<F['CopilotDataUsed']>, keyof S['CopilotDataUsed']>>,
  Assert<Equals<Values<F['CopilotUsage']>, keyof S['CopilotUsage']>>,
  Assert<Equals<Values<F['CopilotUsageWindow']>, keyof S['CopilotUsageWindow']>>,
  Assert<Equals<Values<F['CopilotDataScope']>, keyof S['CopilotDataScope']>>,
  Assert<Equals<Values<F['CopilotConversationSummary']>, keyof S['CopilotConversationSummary']>>,
  Assert<Equals<Values<F['CopilotConversationPage']>, keyof S['CopilotConversationPage']>>,
  Assert<Equals<Values<F['CopilotConversationMessage']>, keyof S['CopilotConversationMessage']>>,
  Assert<Equals<Values<F['CopilotConversation']>, keyof S['CopilotConversation']>>,
  Assert<Equals<Values<F['CopilotFeedbackRequest']>, keyof S['CopilotFeedbackRequest']>>,
]
