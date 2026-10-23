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
/** Natureza da categoria (ADR-0012 §2): EXPENSE serve a despesa e reembolso; INCOME, a receita. */
export const CATEGORY_NATURES = ['EXPENSE', 'INCOME'] as const
export const CARD_BRANDS = ['VISA', 'MASTERCARD', 'ELO', 'AMEX', 'HIPERCARD', 'OTHER'] as const
export const CARD_STATUSES = ['ACTIVE', 'ARCHIVED'] as const
export const INVOICE_STATUSES = ['FUTURE', 'OPEN', 'CLOSED', 'PARTIALLY_PAID', 'PAID', 'OVERDUE'] as const
export const INVOICE_DATES_SOURCES = ['CALCULATED', 'IMPORTED'] as const
export const INVOICE_ITEM_KINDS = ['CHARGE', 'CREDIT'] as const
export const INSTALLMENT_STATUSES = ['PAID', 'CURRENT', 'FUTURE', 'CANCELLED'] as const
export const INSTALLMENT_PLAN_STATUSES = ['ACTIVE', 'FINISHED', 'CANCELLED'] as const
/** Parcelas de uma compra no cartão (InstallmentSplitter.MAX_INSTALLMENTS). */
export const MAX_INSTALLMENTS = 48
export const CARD_NICKNAME_MAX_LENGTH = 100

/** `AccountName.MAX_LENGTH` e `InstitutionName.MAX_LENGTH` do backend: o BFF e o formulário recusam antes. */
export const ACCOUNT_NAME_MAX_LENGTH = 100
export const INSTITUTION_NAME_MAX_LENGTH = 100
/** `TransactionDescription.MAX_LENGTH` do backend. */
export const TRANSACTION_DESCRIPTION_MAX_LENGTH = 200
/** `ListTransactions.MAX_TEXT_LENGTH` (busca) e `MAX_PAGE_SIZE` do backend. */
export const TRANSACTION_SEARCH_MAX_LENGTH = 100
export const TRANSACTION_MAX_PAGE_SIZE = 100
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
    'id', 'workspaceId', 'accountId', 'format', 'status', 'fileName', 'fileSize', 'lines', 'period',
    'sameFileImportedBefore', 'failureReason', 'createdAt', 'previewExpiresAt', 'confirmedAt', 'completedAt',
  ],
  Lines: ['total', 'valid', 'invalid', 'duplicate', 'imported', 'categorized'],
  ImportPeriod: ['from', 'to'],
  ImportRecordResponse: [
    'lineNumber', 'status', 'occurredOn', 'amount', 'direction', 'description', 'issue', 'transactionId',
  ],
  Issue: ['field', 'code'],
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
    'defaultPaymentAccountId', 'status', 'createdAt', 'updatedAt',
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
  InstallmentItemResponse: ['number', 'dueMonth', 'amount', 'cancelledAmount', 'status'],
  ReservedLimitResponse: ['dueMonth', 'amount'],
  InstallmentPlanResponse: [
    'transactionId', 'description', 'purchasedOn', 'total', 'count', 'installmentAmount', 'paidCount', 'paidAmount',
    'currentNumber', 'remainingAfterCurrent', 'lastDueMonth', 'status', 'installments', 'reservedLimit',
  ],
  InstallmentPlanListResponse: ['items'],
  CardsSummaryResponse: [
    'totalLimit', 'utilized', 'openInvoices', 'nextDueDate', 'cards', 'asOf', 'calculationVersion',
  ],
} as const

type F = typeof RECORD_FIELDS
export type FieldChecks = [
  Assert<Equals<Values<F['OverviewResponse']>, keyof O>>,
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
  Assert<Equals<Values<F['InstallmentItemResponse']>, keyof S['InstallmentItemResponse']>>,
  Assert<Equals<Values<F['ReservedLimitResponse']>, keyof S['ReservedLimitResponse']>>,
  Assert<Equals<Values<F['InstallmentPlanResponse']>, keyof S['InstallmentPlanResponse']>>,
  Assert<Equals<Values<F['InstallmentPlanListResponse']>, keyof S['InstallmentPlanListResponse']>>,
]
