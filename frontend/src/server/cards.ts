import 'server-only'
import type { NextRequest, NextResponse } from 'next/server'
import { CARD_BRANDS, CARD_NICKNAME_MAX_LENGTH, INSTITUTION_NAME_MAX_LENGTH, MAX_INSTALLMENTS } from '@/lib/api/contract'
import type { components } from '@/lib/api/schema'
import { isIsoDate } from '@/lib/format/business-date'
import { isUuid } from '@/lib/uuid'
import { backendClient } from './backend'
import { rejectCrossOrigin, stableCode, withSession } from './bff'
import { noStoreJson } from './http'
import {
  amount,
  description,
  invalidRequest,
  readIdempotencyKey,
  readJsonObject,
  validationFailed,
  WORKSPACE_CURRENCY,
  type FieldIssue,
} from './transactions'

type Schemas = components['schemas']
type ApiError = Schemas['ApiError']

/** Mesma regra do domínio (CardNickname, InstitutionName): sem caracteres de controle. */
const CONTROL = /[\u0000-\u001f\u007f-\u009f]/
/** Só os 4 últimos dígitos do cartão (ADR-0025 §1). */
const LAST4 = /^[0-9]{4}$/

const includes = <T extends string>(list: readonly T[], value: unknown): value is T =>
  (list as readonly unknown[]).includes(value)

function text(value: unknown, field: string, max: number, required: boolean, issues: FieldIssue[]): string | null {
  if (value === undefined || value === null || (typeof value === 'string' && value.trim() === '')) {
    if (required) issues.push({ field, code: 'REQUIRED' })
    return null
  }
  if (typeof value !== 'string') {
    issues.push({ field, code: 'INVALID' })
    return null
  }
  const trimmed = value.trim()
  if (trimmed.length > max) issues.push({ field, code: 'TOO_LONG' })
  else if (CONTROL.test(trimmed)) issues.push({ field, code: 'INVALID_CHARACTERS' })
  return trimmed
}

function day(value: unknown, field: string, issues: FieldIssue[]): number | null {
  if (value === undefined || value === null || value === '') {
    issues.push({ field, code: 'REQUIRED' })
    return null
  }
  if (typeof value !== 'number' || !Number.isInteger(value) || value < 1 || value > 31) {
    issues.push({ field, code: 'OUT_OF_RANGE' })
    return null
  }
  return value
}

/**
 * Cadastro mínimo do cartão (ADR-0026 §2 e §10). Monta um objeto novo só com os campos do contrato: número completo,
 * CVV ou validade, se vierem, nunca chegam ao backend nem a log algum. As regras do ciclo são do domínio.
 */
export async function readCardBody(
  request: NextRequest,
): Promise<{ body: Schemas['CardRequest'] } | { response: NextResponse }> {
  const input = await readJsonObject(request)
  if (!input) return { response: invalidRequest() }

  const issues: FieldIssue[] = []
  const nickname = text(input.nickname, 'nickname', CARD_NICKNAME_MAX_LENGTH, true, issues)
  const institutionName = text(input.institutionName, 'institutionName', INSTITUTION_NAME_MAX_LENGTH, false, issues)
  if (input.brand === undefined || input.brand === null || input.brand === '') issues.push({ field: 'brand', code: 'REQUIRED' })
  else if (!includes(CARD_BRANDS, input.brand)) issues.push({ field: 'brand', code: 'INVALID' })
  if (input.last4 === undefined || input.last4 === null || input.last4 === '') issues.push({ field: 'last4', code: 'REQUIRED' })
  else if (typeof input.last4 !== 'string' || !LAST4.test(input.last4)) issues.push({ field: 'last4', code: 'INVALID' })
  const issuesBefore = issues.length
  const limit = amount(input.limit, issues)
  // O validador de valor anota o campo como `amount`; aqui ele se chama `limit`.
  for (let i = issuesBefore; i < issues.length; i++) issues[i] = { field: 'limit', code: issues[i]!.code }
  const closingDay = day(input.closingDay, 'closingDay', issues)
  const dueDay = day(input.dueDay, 'dueDay', issues)
  const account = input.defaultPaymentAccountId
  if (account != null && account !== '' && !isUuid(account)) issues.push({ field: 'defaultPaymentAccountId', code: 'INVALID' })

  if (issues.length > 0) return { response: validationFailed(issues) }
  return {
    body: {
      nickname: nickname!,
      institutionName,
      brand: input.brand as Schemas['CardRequest']['brand'],
      last4: input.last4 as string,
      limit: { amount: limit!, currency: WORKSPACE_CURRENCY },
      closingDay: closingDay!,
      dueDay: dueDay!,
      defaultPaymentAccountId: isUuid(account) ? account : null,
    },
  }
}

function occurredOn(value: unknown, issues: FieldIssue[]): string | null {
  if (!isIsoDate(value)) {
    issues.push({ field: 'occurredOn', code: value ? 'INVALID' : 'REQUIRED' })
    return null
  }
  return value
}

/** Compra no cartão: total, data, descrição e parcelas (1 a 48; ausente = à vista). */
export async function readPurchaseBody(
  request: NextRequest,
): Promise<{ body: Schemas['CardPurchaseRequest'] } | { response: NextResponse }> {
  const input = await readJsonObject(request)
  if (!input) return { response: invalidRequest() }
  const issues: FieldIssue[] = []
  const value = amount(input.amount, issues)
  const date = occurredOn(input.occurredOn, issues)
  const text = description(input.description, issues)
  const count = input.installmentCount ?? 1
  if (typeof count !== 'number' || !Number.isInteger(count) || count < 1 || count > MAX_INSTALLMENTS) {
    issues.push({ field: 'installmentCount', code: 'OUT_OF_RANGE' })
  }
  if (issues.length > 0) return { response: validationFailed(issues) }
  return {
    body: {
      amount: { amount: value!, currency: WORKSPACE_CURRENCY },
      occurredOn: date!,
      description: text!,
      installmentCount: count as number,
    },
  }
}

/** Pagamento de fatura: conta de origem, valor, data e descrição opcional. */
export async function readPaymentBody(
  request: NextRequest,
): Promise<{ body: Schemas['CardPaymentRequest'] } | { response: NextResponse }> {
  const input = await readJsonObject(request)
  if (!input) return { response: invalidRequest() }
  const issues: FieldIssue[] = []
  if (!isUuid(input.accountId)) issues.push({ field: 'accountId', code: input.accountId ? 'INVALID' : 'REQUIRED' })
  const value = amount(input.amount, issues)
  const date = occurredOn(input.occurredOn, issues)
  const hasText = typeof input.description === 'string' && input.description.trim() !== ''
  const text = hasText ? description(input.description, issues) : null
  if (issues.length > 0) return { response: validationFailed(issues) }
  return {
    body: {
      accountId: input.accountId as string,
      amount: { amount: value!, currency: WORKSPACE_CURRENCY },
      occurredOn: date!,
      description: text,
    },
  }
}

/** Estorno no cartão: valor, data, descrição e vínculo opcional com uma compra dele. */
export async function readCreditBody(
  request: NextRequest,
): Promise<{ body: Schemas['CardCreditRequest'] } | { response: NextResponse }> {
  const input = await readJsonObject(request)
  if (!input) return { response: invalidRequest() }
  const issues: FieldIssue[] = []
  const value = amount(input.amount, issues)
  const date = occurredOn(input.occurredOn, issues)
  const text = description(input.description, issues)
  const link = input.refundOfTransactionId
  if (link != null && !isUuid(link)) issues.push({ field: 'refundOfTransactionId', code: 'INVALID' })
  if (issues.length > 0) return { response: validationFailed(issues) }
  return {
    body: {
      amount: { amount: value!, currency: WORKSPACE_CURRENCY },
      occurredOn: date!,
      description: text!,
      refundOfTransactionId: isUuid(link) ? link : null,
    },
  }
}

const FIELDS = new Set([
  'nickname', 'institutionName', 'brand', 'last4', 'limit', 'closingDay', 'dueDay', 'defaultPaymentAccountId',
  'amount', 'currency', 'occurredOn', 'description', 'installmentCount', 'accountId', 'refundOfTransactionId',
])
/** Códigos da Bean Validation viram os do domínio; os do domínio passam se forem códigos estáveis. */
const BEAN_CODES: Partial<Record<string, string>> = { NotBlank: 'REQUIRED', NotNull: 'REQUIRED', Size: 'TOO_LONG' }

/**
 * Erros da API de cartões → códigos do BFF. Só passam códigos estáveis do contrato (e, na validação, só `field`
 * conhecido + `code` estável); mensagens, `traceId` e ids do backend nunca chegam ao browser nem aos logs.
 */
export function cardFailure(label: string, status: number, error: ApiError | undefined): NextResponse {
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
  if (status === 404 && (code === 'CARD_NOT_FOUND' || code === 'ACCOUNT_NOT_FOUND' || code === 'TRANSACTION_NOT_FOUND')) {
    return noStoreJson({ code }, 404)
  }
  if (status === 409 && (code === 'CARD_ARCHIVED' || code === 'ACCOUNT_ARCHIVED' || code === 'CONFLICT')) {
    return noStoreJson({ code }, 409)
  }
  if (status === 422 && code === 'IDEMPOTENCY_KEY_REUSED') return noStoreJson({ code }, 422)
  console.error(`${label} upstream failure`, { status })
  return noStoreJson({ code: 'UNAVAILABLE' }, 502)
}

const CARD_OPERATION_PATHS = {
  purchases: '/api/v1/workspaces/{workspaceId}/cards/{cardId}/purchases',
  payments: '/api/v1/workspaces/{workspaceId}/cards/{cardId}/payments',
  credits: '/api/v1/workspaces/{workspaceId}/cards/{cardId}/credits',
} as const

const READERS = { purchases: readPurchaseBody, payments: readPaymentBody, credits: readCreditBody } as const

/**
 * Compra, pagamento de fatura ou estorno no cartão (ADR-0026 §10). Exige `Origin` do app e a `Idempotency-Key` do
 * browser, repassada sem alteração: retry ou duplo clique com a mesma chave devolvem o lançamento original (200).
 */
export async function recordCardOperation(
  request: NextRequest,
  params: Promise<{ cardId: string }>,
  operation: keyof typeof CARD_OPERATION_PATHS,
): Promise<NextResponse> {
  const forbidden = rejectCrossOrigin(request)
  if (forbidden) return forbidden
  const { cardId } = await params
  if (!isUuid(cardId)) return noStoreJson({ code: 'CARD_NOT_FOUND' }, 404)

  const label = `card ${operation}`
  return withSession(label, async (session) => {
    const idempotencyKey = readIdempotencyKey(request)
    if (!idempotencyKey) return noStoreJson({ code: 'INVALID_REQUEST' }, 400)
    const parsed = await READERS[operation](request)
    if ('response' in parsed) return parsed.response

    const { data, error, response } = await backendClient(session.accessToken).POST(CARD_OPERATION_PATHS[operation], {
      params: { path: { workspaceId: session.workspaceId, cardId }, header: { 'Idempotency-Key': idempotencyKey } },
      // O corpo já foi remontado pelo leitor da operação; o cast só une os três formatos de corpo.
      body: parsed.body as never,
    })
    if (data) return noStoreJson(data, response.status === 201 ? 201 : 200)
    return cardFailure(label, response.status, error)
  })
}
