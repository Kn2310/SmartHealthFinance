import 'server-only'
import type { NextRequest, NextResponse } from 'next/server'
import {
  COPILOT_CONTEXT_KINDS,
  COPILOT_FEEDBACK_RATINGS,
  COPILOT_FEEDBACK_REASONS,
  COPILOT_MAX_PREVIOUS_QUESTIONS,
  COPILOT_QUESTION_MAX_LENGTH,
} from '@/lib/api/contract'
import type { components } from '@/lib/api/schema'
import { isUuid } from '@/lib/uuid'
import { backendClient } from './backend'
import { rejectCrossOrigin, stableCode, withSession } from './bff'
import { noStoreJson } from './http'
import { readIdempotencyKey } from './transactions'

/**
 * BFF do Copilot (ADR-0040, ADR-0041 §7). O corpo é remontado só com os campos do contrato; falhas chegam ao browser
 * como códigos estáveis do ADR-0040 §4. Nada de pergunta, resposta ou valor vai para log.
 */
type Schemas = components['schemas']
type ApiError = Schemas['ApiError']

/** 500 + 3 × 500 caracteres, com folga para escapes JSON. */
const MAX_BODY_BYTES = 16 * 1024
/** Acima do orçamento de 30 s do pipeline (ADR-0039 §2, ADR-0041 §7). */
const ASK_TIMEOUT_MS = 40_000
const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/
const CURSOR = /^[A-Za-z0-9_\-=.+/~:]{1,256}$/
const MAX_PAGE_SIZE = 50

const validation = () => noStoreJson({ code: 'VALIDATION_FAILED' }, 400)
const includes = <T extends string>(list: readonly T[], value: unknown): value is T =>
  (list as readonly unknown[]).includes(value)

// --- corpo da pergunta ---

type AskBody = Schemas['CopilotAskQuestionRequest']
type EntryContext = NonNullable<AskBody['context']>

function readContext(value: unknown): EntryContext | null | undefined {
  if (value === undefined || value === null) return null
  if (typeof value !== 'object' || Array.isArray(value)) return undefined
  const c = value as Record<string, unknown>
  if (!includes(COPILOT_CONTEXT_KINDS, c.kind)) return undefined
  switch (c.kind) {
    case 'INSIGHT':
      return isUuid(c.insightId) ? { kind: 'INSIGHT', insightId: c.insightId } : undefined
    case 'INVOICE':
      return isUuid(c.cardId) && typeof c.month === 'string' && MONTH.test(c.month)
        ? { kind: 'INVOICE', cardId: c.cardId, month: c.month }
        : undefined
    case 'INSTALLMENT_PLAN':
      return isUuid(c.cardId) && isUuid(c.planId)
        ? { kind: 'INSTALLMENT_PLAN', cardId: c.cardId, planId: c.planId }
        : undefined
    case 'GOAL':
      return isUuid(c.goalId) ? { kind: 'GOAL', goalId: c.goalId } : undefined
  }
}

function questionText(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const text = value.trim()
  return text.length >= 1 && text.length <= COPILOT_QUESTION_MAX_LENGTH ? text : null
}

/** Pergunta de 1 a 500 caracteres, até 3 anteriores, `conversationId` e `previousQuestions` nunca juntos. */
export async function readAskBody(request: NextRequest): Promise<{ body: AskBody } | { response: NextResponse }> {
  const raw = await request.text().catch(() => null)
  if (raw === null || raw.length > MAX_BODY_BYTES) return { response: validation() }
  let input: unknown
  try {
    input = JSON.parse(raw)
  } catch {
    return { response: validation() }
  }
  if (typeof input !== 'object' || input === null || Array.isArray(input)) return { response: validation() }
  const fields = input as Record<string, unknown>

  const question = questionText(fields.question)
  if (question === null) return { response: validation() }

  let conversationId: string | null = null
  if (fields.conversationId !== undefined && fields.conversationId !== null) {
    if (!isUuid(fields.conversationId)) return { response: validation() }
    conversationId = fields.conversationId
  }

  let previous: string[] = []
  if (fields.previousQuestions !== undefined && fields.previousQuestions !== null) {
    if (!Array.isArray(fields.previousQuestions) || fields.previousQuestions.length > COPILOT_MAX_PREVIOUS_QUESTIONS) {
      return { response: validation() }
    }
    const texts = fields.previousQuestions.map(questionText)
    if (texts.some((text) => text === null)) return { response: validation() }
    previous = texts as string[]
  }
  if (conversationId && previous.length > 0) return { response: validation() }

  const context = readContext(fields.context)
  if (context === undefined) return { response: validation() }

  const body: AskBody = { question }
  if (conversationId) body.conversationId = conversationId
  if (previous.length > 0) body.previousQuestions = previous
  if (context) body.context = context
  return { body }
}

export async function readFeedbackBody(
  request: NextRequest,
): Promise<{ body: Schemas['CopilotFeedbackRequest'] } | { response: NextResponse }> {
  const raw = await request.text().catch(() => null)
  if (raw === null || raw.length > 1024) return { response: validation() }
  let input: unknown
  try {
    input = JSON.parse(raw)
  } catch {
    return { response: validation() }
  }
  if (typeof input !== 'object' || input === null || Array.isArray(input)) return { response: validation() }
  const { rating, reason } = input as Record<string, unknown>
  if (!includes(COPILOT_FEEDBACK_RATINGS, rating)) return { response: validation() }
  if (reason !== undefined && reason !== null && !includes(COPILOT_FEEDBACK_REASONS, reason)) {
    return { response: validation() }
  }
  const body: Schemas['CopilotFeedbackRequest'] = { rating }
  if (rating === 'NOT_HELPFUL' && reason) body.reason = reason as Schemas['CopilotFeedbackRequest']['reason']
  return { body }
}

// --- falhas ---

const KNOWN: Record<number, ReadonlySet<string>> = {
  403: new Set(['COPILOT_CONSENT_REQUIRED']),
  404: new Set(['COPILOT_CONVERSATION_NOT_FOUND', 'COPILOT_CONTEXT_NOT_FOUND', 'COPILOT_REQUEST_NOT_FOUND']),
  409: new Set(['COPILOT_REQUEST_IN_PROGRESS', 'COPILOT_ALREADY_ANSWERED']),
  422: new Set(['IDEMPOTENCY_KEY_REUSED']),
  503: new Set(['COPILOT_UNAVAILABLE']),
}

/** Segundos do `Retry-After`, de 1 a 3600; qualquer outra coisa vira 1 (o backend sempre manda inteiro ≥ 1). */
function retryAfterSeconds(header: string | null): number {
  const seconds = Number(header)
  return Number.isInteger(seconds) && seconds >= 1 ? Math.min(seconds, 3600) : 1
}

/** Erros da API do Copilot → códigos do BFF; mensagens, `traceId` e detalhes nunca chegam ao browser nem ao log. */
export function copilotFailure(
  label: string,
  status: number,
  error: ApiError | Schemas['QuotaExceededError'] | undefined,
  headers?: Headers,
): NextResponse {
  const code = stableCode(error?.code)
  if (status === 401) return noStoreJson({ code: 'UNAUTHENTICATED' }, 401)
  if (status === 400) return validation()
  if (status === 429 && code === 'COPILOT_RATE_LIMITED') {
    const retryAfter = retryAfterSeconds(headers?.get('retry-after') ?? null)
    const response = noStoreJson({ code, retryAfter }, 429)
    response.headers.set('Retry-After', String(retryAfter))
    return response
  }
  if (status === 429 && code === 'COPILOT_QUOTA_EXCEEDED') {
    const resetsAt = (error as Schemas['QuotaExceededError'] | undefined)?.resetsAt
    const parsed = typeof resetsAt === 'string' ? new Date(resetsAt) : null
    return noStoreJson({ code, resetsAt: parsed && !Number.isNaN(parsed.getTime()) ? parsed.toISOString() : null }, 429)
  }
  if (code && KNOWN[status]?.has(code)) return noStoreJson({ code }, status)
  console.error(`${label} upstream failure`, { status })
  return noStoreJson({ code: 'UNAVAILABLE' }, 502)
}

// --- handlers das rotas ---

/** Pergunta síncrona; `Idempotency-Key` do browser repassada sem alteração (ADR-0040 §5). */
export async function askQuestion(request: NextRequest): Promise<NextResponse> {
  const forbidden = rejectCrossOrigin(request)
  if (forbidden) return forbidden
  return withSession('copilot ask', async (session) => {
    const key = readIdempotencyKey(request)
    if (!key) return validation()
    const parsed = await readAskBody(request)
    if ('response' in parsed) return parsed.response
    const { data, error, response } = await backendClient(session.accessToken).POST('/api/v1/workspaces/{workspaceId}/copilot/questions', {
      params: { path: { workspaceId: session.workspaceId }, header: { 'Idempotency-Key': key } },
      body: parsed.body,
      signal: AbortSignal.timeout(ASK_TIMEOUT_MS),
    })
    if (data) return noStoreJson(data)
    return copilotFailure('copilot ask', response.status, error, response.headers)
  })
}

export async function getUsage(): Promise<NextResponse> {
  return withSession('copilot usage', async (session) => {
    const { data, error, response } = await backendClient(session.accessToken).GET('/api/v1/workspaces/{workspaceId}/copilot/usage', {
      params: { path: { workspaceId: session.workspaceId } },
    })
    if (data) return noStoreJson(data)
    return copilotFailure('copilot usage', response.status, error)
  })
}

export async function listConversations(request: NextRequest): Promise<NextResponse> {
  const search = request.nextUrl.searchParams
  const limitParam = Number(search.get('limit'))
  const before = search.get('before')
  const query: { limit?: number; before?: string } = {}
  if (search.get('limit') !== null && Number.isInteger(limitParam) && limitParam >= 1) {
    query.limit = Math.min(limitParam, MAX_PAGE_SIZE)
  }
  if (before !== null) {
    if (!CURSOR.test(before)) return validation()
    query.before = before
  }
  return withSession('copilot conversations', async (session) => {
    const { data, error, response } = await backendClient(session.accessToken).GET('/api/v1/workspaces/{workspaceId}/copilot/conversations', {
      params: { path: { workspaceId: session.workspaceId }, query },
    })
    if (data) return noStoreJson(data)
    return copilotFailure('copilot conversations', response.status, error)
  })
}

export async function getConversation(conversationId: string): Promise<NextResponse> {
  if (!isUuid(conversationId)) return noStoreJson({ code: 'COPILOT_CONVERSATION_NOT_FOUND' }, 404)
  return withSession('copilot conversation', async (session) => {
    const { data, error, response } = await backendClient(session.accessToken).GET(
      '/api/v1/workspaces/{workspaceId}/copilot/conversations/{conversationId}',
      { params: { path: { workspaceId: session.workspaceId, conversationId } } },
    )
    if (data) return noStoreJson(data)
    return copilotFailure('copilot conversation', response.status, error)
  })
}

export async function deleteConversation(request: NextRequest, conversationId: string): Promise<NextResponse> {
  const forbidden = rejectCrossOrigin(request)
  if (forbidden) return forbidden
  if (!isUuid(conversationId)) return noStoreJson({ code: 'COPILOT_CONVERSATION_NOT_FOUND' }, 404)
  return withSession('copilot conversation delete', async (session) => {
    const { error, response } = await backendClient(session.accessToken).DELETE(
      '/api/v1/workspaces/{workspaceId}/copilot/conversations/{conversationId}',
      { params: { path: { workspaceId: session.workspaceId, conversationId } } },
    )
    if (response.status === 204) return noStoreJson({ deleted: true })
    return copilotFailure('copilot conversation delete', response.status, error)
  })
}

export async function deleteAllConversations(request: NextRequest): Promise<NextResponse> {
  const forbidden = rejectCrossOrigin(request)
  if (forbidden) return forbidden
  return withSession('copilot conversations delete', async (session) => {
    const { error, response } = await backendClient(session.accessToken).DELETE('/api/v1/workspaces/{workspaceId}/copilot/conversations', {
      params: { path: { workspaceId: session.workspaceId } },
    })
    if (response.status === 204) return noStoreJson({ deleted: true })
    return copilotFailure('copilot conversations delete', response.status, error)
  })
}

export async function sendFeedback(request: NextRequest, requestId: string): Promise<NextResponse> {
  const forbidden = rejectCrossOrigin(request)
  if (forbidden) return forbidden
  if (!isUuid(requestId)) return noStoreJson({ code: 'COPILOT_REQUEST_NOT_FOUND' }, 404)
  return withSession('copilot feedback', async (session) => {
    const parsed = await readFeedbackBody(request)
    if ('response' in parsed) return parsed.response
    const { error, response } = await backendClient(session.accessToken).PUT(
      '/api/v1/workspaces/{workspaceId}/copilot/questions/{requestId}/feedback',
      { params: { path: { workspaceId: session.workspaceId, requestId } }, body: parsed.body },
    )
    if (response.status === 204) return noStoreJson({ saved: true })
    return copilotFailure('copilot feedback', response.status, error)
  })
}
