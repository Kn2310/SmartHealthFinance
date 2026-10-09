import 'server-only'
import type { NextRequest, NextResponse } from 'next/server'
import {
  ADJUSTMENT_DIRECTIONS,
  TRANSACTION_DESCRIPTION_MAX_LENGTH,
  TRANSACTION_SEARCH_MAX_LENGTH,
  TRANSACTION_STATUSES,
  TRANSACTION_TYPES,
} from '@/lib/api/contract'
import type { components } from '@/lib/api/schema'
import { businessToday, isIsoDate } from '@/lib/format/business-date'
import { isUuid } from '@/lib/uuid'
import { backendClient } from './backend'
import { rejectCrossOrigin, stableCode, withSession } from './bff'
import { noStoreJson } from './http'
import { IDEMPOTENCY_KEY } from './imports'

type Schemas = components['schemas']
type ApiError = Schemas['ApiError']
type Transaction = Schemas['TransactionResponse']
type TransactionType = Transaction['type']
type TransactionStatus = Transaction['status']
type AdjustmentDirection = NonNullable<Transaction['adjustmentDirection']>
export type CreateTransactionBody = Schemas['CreateTransactionRequest']

/** Moeda base do Workspace (BRL fixa no MVP — ADR-0004 §7). O backend recusa moeda diferente da conta. */
export const WORKSPACE_CURRENCY = 'BRL'

/** Lançamento manual pela UI: receita, despesa e ajuste. Transferência e reembolso pela UI ficam fora do escopo. */
export const MANUAL_TYPES = ['INCOME', 'EXPENSE', 'ADJUSTMENT'] as const satisfies readonly TransactionType[]
/** Criação aceita só PENDING ou POSTED (ADR-0005 §7). */
const INITIAL_STATUSES = ['PENDING', 'POSTED'] as const satisfies readonly TransactionStatus[]

/** Página fixa da lista no BFF: o browser não escolhe o tamanho (≤ 100 no backend). */
export const PAGE_SIZE = 50
const MAX_PAGE = 10_000

/** O corpo de criar tem 7 campos curtos: qualquer coisa muito maior nem é lida. */
const MAX_BODY_BYTES = 4 * 1024

/** Decimal simples como o `Money.of` do backend aceita; casas e positividade são regras do domínio. */
const PLAIN_DECIMAL = /^\d{1,15}(\.\d{1,4})?$/
/** Mesma regra do domínio (TransactionDescription): sem caracteres de controle. */
const CONTROL = /[\u0000-\u001f\u007f-\u009f]/

const includes = <T extends string>(list: readonly T[], value: unknown): value is T =>
  (list as readonly unknown[]).includes(value)

type FieldIssue = { field: string; code: string }
const validationFailed = (details: FieldIssue[]) => noStoreJson({ code: 'VALIDATION_FAILED', details }, 400)
const invalidRequest = () => noStoreJson({ code: 'INVALID_REQUEST' }, 400)

/** Filtros da lista aceitos na query do BFF. Valores fora do formato são descartados, nunca repassados. */
export interface ListQuery {
  from?: string
  to?: string
  type?: TransactionType
  status?: TransactionStatus
  accountId?: string
  q?: string
  page: number
  pageSize: number
}

export function parseListQuery(search: URLSearchParams): ListQuery {
  const from = search.get('from')
  const to = search.get('to')
  const type = search.get('type')
  const status = search.get('status')
  const accountId = search.get('accountId')
  const q = search.get('q')?.trim().slice(0, TRANSACTION_SEARCH_MAX_LENGTH)
  const pageParam = Number(search.get('page') ?? '0')
  return {
    from: isIsoDate(from) ? from : undefined,
    to: isIsoDate(to) ? to : undefined,
    type: includes(TRANSACTION_TYPES, type) ? type : undefined,
    status: includes(TRANSACTION_STATUSES, status) ? status : undefined,
    accountId: isUuid(accountId) ? accountId : undefined,
    q: q && !CONTROL.test(q) ? q : undefined,
    page: Number.isInteger(pageParam) && pageParam >= 0 && pageParam < MAX_PAGE ? pageParam : 0,
    pageSize: PAGE_SIZE,
  }
}

/** `Idempotency-Key` do browser: obrigatória e no formato do backend (ADR-0005 §11). */
export function readIdempotencyKey(request: NextRequest): string | null {
  const key = request.headers.get('idempotency-key')
  return key && IDEMPOTENCY_KEY.test(key) ? key : null
}

export async function readJsonObject(request: NextRequest): Promise<Record<string, unknown> | null> {
  const raw = await request.text().catch(() => null)
  if (raw === null || raw.length > MAX_BODY_BYTES) return null
  try {
    const body: unknown = JSON.parse(raw)
    return typeof body === 'object' && body !== null && !Array.isArray(body) ? (body as Record<string, unknown>) : null
  } catch {
    return null
  }
}

function description(value: unknown, issues: FieldIssue[]): string | null {
  if (value === undefined || value === null || (typeof value === 'string' && value.trim() === '')) {
    issues.push({ field: 'description', code: 'REQUIRED' })
    return null
  }
  if (typeof value !== 'string') {
    issues.push({ field: 'description', code: 'INVALID' })
    return null
  }
  const trimmed = value.trim()
  if (trimmed.length > TRANSACTION_DESCRIPTION_MAX_LENGTH) issues.push({ field: 'description', code: 'TOO_LONG' })
  else if (CONTROL.test(trimmed)) issues.push({ field: 'description', code: 'INVALID_CHARACTERS' })
  return trimmed
}

function amount(value: unknown, issues: FieldIssue[]): string | null {
  if (value === undefined || value === null || value === '') {
    issues.push({ field: 'amount', code: 'REQUIRED' })
    return null
  }
  // Sempre string: um `number` em JSON já pode ter perdido precisão no caminho.
  if (typeof value !== 'string' || !PLAIN_DECIMAL.test(value)) {
    issues.push({ field: 'amount', code: 'INVALID_FORMAT' })
    return null
  }
  return value
}

function direction(value: unknown, issues: FieldIssue[]): AdjustmentDirection | null {
  if (value === undefined || value === null || value === '') {
    issues.push({ field: 'adjustmentDirection', code: 'REQUIRED' })
    return null
  }
  if (!includes(ADJUSTMENT_DIRECTIONS, value)) {
    issues.push({ field: 'adjustmentDirection', code: 'INVALID' })
    return null
  }
  return value
}

/**
 * Lê e valida o lançamento manual. Monta um objeto novo só com os campos do contrato (o valor vai como string
 * em `MoneyDto`); nada além disso chega ao backend. Em caso de recusa, devolve a resposta 400 pronta.
 */
export async function readCreateBody(
  request: NextRequest,
): Promise<{ body: CreateTransactionBody } | { response: NextResponse }> {
  const input = await readJsonObject(request)
  if (!input) return { response: invalidRequest() }

  const issues: FieldIssue[] = []
  const type = input.type
  if (type === undefined || type === null || type === '') issues.push({ field: 'type', code: 'REQUIRED' })
  else if (!includes(MANUAL_TYPES, type)) issues.push({ field: 'type', code: 'INVALID' })

  if (!isUuid(input.accountId)) issues.push({ field: 'accountId', code: input.accountId ? 'INVALID' : 'REQUIRED' })
  const value = amount(input.amount, issues)
  if (!isIsoDate(input.occurredOn)) issues.push({ field: 'occurredOn', code: input.occurredOn ? 'INVALID' : 'REQUIRED' })
  const text = description(input.description, issues)

  const status = input.status ?? 'POSTED'
  if (!includes(INITIAL_STATUSES, status)) issues.push({ field: 'status', code: 'INVALID' })

  const adjustmentDirection = type === 'ADJUSTMENT' ? direction(input.adjustmentDirection, issues) : null
  if (type !== 'ADJUSTMENT' && input.adjustmentDirection != null) {
    issues.push({ field: 'adjustmentDirection', code: 'NOT_ALLOWED' })
  }

  if (issues.length > 0) return { response: validationFailed(issues) }
  return {
    body: {
      type: type as TransactionType,
      accountId: input.accountId as string,
      adjustmentDirection,
      amount: { amount: value!, currency: WORKSPACE_CURRENCY },
      occurredOn: input.occurredOn as string,
      description: text!,
      status: status as (typeof INITIAL_STATUSES)[number],
    },
  }
}

/** Corpo do `PUT`: só a descrição é editável (ADR-0005 §8). */
export async function readDescriptionBody(
  request: NextRequest,
): Promise<{ body: Schemas['UpdateTransactionRequest'] } | { response: NextResponse }> {
  const input = await readJsonObject(request)
  if (!input) return { response: invalidRequest() }
  const issues: FieldIssue[] = []
  const text = description(input.description, issues)
  if (issues.length > 0) return { response: validationFailed(issues) }
  return { body: { description: text! } }
}

/** Saldo inicial de uma conta nova (ADR-0004 §4): valor positivo + direção, como num ADJUSTMENT. */
export interface OpeningBalance {
  amount: string
  direction: AdjustmentDirection
}

/** `openingBalance` opcional no corpo de criar conta. `undefined` = sem saldo inicial. */
export function readOpeningBalance(value: unknown): { value: OpeningBalance | undefined } | { response: NextResponse } {
  if (value === undefined || value === null) return { value: undefined }
  if (typeof value !== 'object' || Array.isArray(value)) return { response: invalidRequest() }
  const input = value as Record<string, unknown>
  const issues: FieldIssue[] = []
  const parsed = amount(input.amount, issues)
  // Pré-checagem só para não deixar a conta criada sem o saldo por um valor que o domínio recusaria (BRL: 2 casas,
  // positivo). A regra continua no backend.
  if (parsed !== null) {
    const [integer = '', fraction = ''] = parsed.split('.')
    if (/^0*$/.test(integer + fraction)) issues.push({ field: 'amount', code: 'NOT_POSITIVE' })
    else if (fraction.replace(/0+$/, '').length > 2) issues.push({ field: 'amount', code: 'TOO_MANY_DECIMALS' })
  }
  const dir = direction(input.direction, issues)
  if (issues.length > 0) {
    const field = (name: string) => `openingBalance.${name === 'adjustmentDirection' ? 'direction' : name}`
    return { response: validationFailed(issues.map((issue) => ({ field: field(issue.field), code: issue.code }))) }
  }
  return { value: { amount: parsed!, direction: dir! } }
}

export const OPENING_BALANCE_DESCRIPTION = 'Saldo inicial'

/**
 * Chave determinística por conta: retry, duplo clique ou aba reaberta repetem a MESMA intenção, então nunca
 * existem dois saldos iniciais lançados por este fluxo (mesmo conteúdo → replay; outro valor → 422).
 */
export const openingBalanceKey = (accountId: string) => `opening-balance:${accountId}`

export type OpeningBalanceOutcome = 'RECORDED' | 'ALREADY_RECORDED' | 'FAILED'

/**
 * Lança o saldo inicial como `ADJUSTMENT` POSTED, datado de hoje no fuso de negócio. Não é atômico com a
 * criação da conta (decisão: BFF orquestra; ver ADR-0004 §11): a falha é devolvida, nunca escondida, e a UI
 * oferece tentar de novo com a mesma chave.
 */
export async function recordOpeningBalance(
  client: ReturnType<typeof backendClient>,
  workspaceId: string,
  accountId: string,
  opening: OpeningBalance,
): Promise<{ outcome: OpeningBalanceOutcome; status: number; error?: ApiError }> {
  try {
    const { data, error, response } = await client.POST('/api/v1/workspaces/{workspaceId}/transactions', {
      params: { path: { workspaceId }, header: { 'Idempotency-Key': openingBalanceKey(accountId) } },
      body: {
        type: 'ADJUSTMENT',
        accountId,
        adjustmentDirection: opening.direction,
        amount: { amount: opening.amount, currency: WORKSPACE_CURRENCY },
        occurredOn: businessToday(),
        description: OPENING_BALANCE_DESCRIPTION,
        status: 'POSTED',
      },
    })
    if (data) return { outcome: 'RECORDED', status: response.status }
    const code = stableCode(error?.code)
    // A chave já foi usada com outro conteúdo (outro valor ou outro dia): o saldo inicial desta conta já existe.
    if (response.status === 422 && code === 'IDEMPOTENCY_KEY_REUSED') {
      return { outcome: 'ALREADY_RECORDED', status: response.status }
    }
    return { outcome: 'FAILED', status: response.status, error }
  } catch {
    return { outcome: 'FAILED', status: 502 }
  }
}

const TRANSITION_PATHS = {
  post: '/api/v1/workspaces/{workspaceId}/transactions/{transactionId}/post',
  cancel: '/api/v1/workspaces/{workspaceId}/transactions/{transactionId}/cancel',
  reverse: '/api/v1/workspaces/{workspaceId}/transactions/{transactionId}/reverse',
} as const

/**
 * Efetivar (PENDING → POSTED), cancelar (PENDING → CANCELLED) ou estornar (POSTED → REVERSED). Idempotente no
 * backend; transição inválida → 409 `TRANSACTION_STATUS_CONFLICT` (ADR-0005 §7). Exige `Origin` do app.
 */
export async function transitionTransaction(
  request: NextRequest,
  params: Promise<{ transactionId: string }>,
  action: keyof typeof TRANSITION_PATHS,
): Promise<NextResponse> {
  const forbidden = rejectCrossOrigin(request)
  if (forbidden) return forbidden
  const { transactionId } = await params
  if (!isUuid(transactionId)) return noStoreJson({ code: 'TRANSACTION_NOT_FOUND' }, 404)

  const label = `transaction ${action}`
  return withSession(label, async (session) => {
    const { data, error, response } = await backendClient(session.accessToken).POST(TRANSITION_PATHS[action], {
      params: { path: { workspaceId: session.workspaceId, transactionId } },
    })
    if (data) return noStoreJson(data)
    return transactionFailure(label, response.status, error)
  })
}

/** Campos que a UI conhece; `Idempotency-Key` recusada vira `INVALID_REQUEST` (problema do cliente, não do campo). */
const FIELDS = new Set([
  'type', 'accountId', 'adjustmentDirection', 'amount', 'currency', 'occurredOn', 'description', 'status', 'from', 'to',
  'q', 'page', 'pageSize',
])
/** Códigos da Bean Validation viram os do domínio; os do domínio passam se forem códigos estáveis. */
const BEAN_CODES: Partial<Record<string, string>> = { NotBlank: 'REQUIRED', NotNull: 'REQUIRED', Size: 'TOO_LONG' }

/**
 * Erros da API de transações → códigos do BFF. Só passam códigos estáveis do contrato (e, na validação, só
 * `field` conhecido + `code` estável); mensagens, `traceId` e IDs do backend nunca chegam ao browser nem aos logs.
 */
export function transactionFailure(label: string, status: number, error: ApiError | undefined): NextResponse {
  const code = stableCode(error?.code)
  if (status === 401) return noStoreJson({ code: 'UNAUTHENTICATED' }, 401)
  if (status === 400 && code === 'VALIDATION_FAILED') {
    const details = (error?.details ?? []).flatMap((detail) => {
      if (!detail.field || !FIELDS.has(detail.field)) return []
      const field = detail.field === 'currency' ? 'amount' : detail.field
      return [{ field, code: (detail.code && BEAN_CODES[detail.code]) ?? stableCode(detail.code) ?? 'INVALID' }]
    })
    return details.length > 0 ? validationFailed(details) : invalidRequest()
  }
  if (status === 400) return invalidRequest()
  if (status === 404 && (code === 'TRANSACTION_NOT_FOUND' || code === 'ACCOUNT_NOT_FOUND')) return noStoreJson({ code }, 404)
  if (status === 409 && (code === 'TRANSACTION_STATUS_CONFLICT' || code === 'ACCOUNT_ARCHIVED' || code === 'CONFLICT')) {
    return noStoreJson({ code }, 409)
  }
  if (status === 422 && code === 'IDEMPOTENCY_KEY_REUSED') return noStoreJson({ code }, 422)
  console.error(`${label} upstream failure`, { status })
  return noStoreJson({ code: 'UNAVAILABLE' }, 502)
}
