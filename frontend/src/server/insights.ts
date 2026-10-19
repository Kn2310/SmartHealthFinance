import 'server-only'
import type { NextRequest, NextResponse } from 'next/server'
import type { components } from '@/lib/api/schema'
import { isUuid } from '@/lib/uuid'
import { stableCode } from './bff'
import { noStoreJson } from './http'

type ApiError = components['schemas']['ApiError']

/** Parâmetros da lista aceitos pelo BFF (ADR-0023 §1); o Workspace vem SEMPRE da sessão. */
export interface InsightListQuery {
  importance?: 'IMPORTANT' | 'INFORMATIVE'
  type?: 'SPENDING' | 'INCOME' | 'HEALTH' | 'CARDS'
  state?: 'ACTIVE' | 'DISMISSED'
  page?: number
  pageSize?: number
}

const IMPORTANCES = ['IMPORTANT', 'INFORMATIVE'] as const
const TYPES = ['SPENDING', 'INCOME', 'HEALTH', 'CARDS'] as const
const STATES = ['ACTIVE', 'DISMISSED'] as const
export const DIMENSIONS = ['CASH_FLOW', 'SPENDING', 'CREDIT', 'DEBT', 'RESERVE', 'CONSISTENCY'] as const
export type DimensionParam = (typeof DIMENSIONS)[number]

const oneOf = <T extends string>(list: readonly T[], value: string | null): T | undefined =>
  value !== null && (list as readonly string[]).includes(value) ? (value as T) : undefined

function intIn(value: string | null, min: number, max: number): number | undefined {
  if (value === null || !/^\d{1,5}$/.test(value)) return undefined
  const n = Number(value)
  return n >= min && n <= max ? n : undefined
}

/** Só valores bem formados passam; o resto é descartado e cai no padrão do backend. */
export function parseInsightListQuery(search: URLSearchParams): InsightListQuery {
  return {
    importance: oneOf(IMPORTANCES, search.get('importance')),
    type: oneOf(TYPES, search.get('type')),
    state: oneOf(STATES, search.get('state')),
    page: intIn(search.get('page'), 0, 10_000),
    pageSize: intIn(search.get('pageSize'), 1, 50),
  }
}

/** `related`: exatamente um de `transactionId` (UUID) e `dimension`; senão, nenhum (o backend responde 400). */
export function parseRelatedQuery(search: URLSearchParams): { transactionId?: string; dimension?: DimensionParam } {
  const transactionId = search.get('transactionId')
  return {
    transactionId: transactionId && isUuid(transactionId) ? transactionId : undefined,
    dimension: oneOf(DIMENSIONS, search.get('dimension')),
  }
}

const FIELDS = new Set(['importance', 'type', 'state', 'page', 'pageSize', 'transactionId', 'dimension', 'value'])
const NOT_FOUND = new Set(['INSIGHT_NOT_FOUND', 'TRANSACTION_NOT_FOUND'])

/** Falhas do backend → códigos estáveis (ADR-0023 §1); só campos conhecidos chegam ao browser. */
export function insightFailure(label: string, status: number, error: ApiError | undefined): NextResponse {
  if (status === 401) return noStoreJson({ code: 'UNAUTHENTICATED' }, 401)
  const code = stableCode(error?.code)
  if (status === 404 && code && NOT_FOUND.has(code)) return noStoreJson({ code }, 404)
  if (status === 409 && code === 'INSIGHT_NOT_ACTIVE') return noStoreJson({ code }, 409)
  if (status === 400 && code === 'VALIDATION_FAILED') {
    const details = (error?.details ?? []).flatMap((detail) =>
      detail.field && FIELDS.has(detail.field) ? [{ field: detail.field, code: stableCode(detail.code) ?? 'INVALID' }] : [],
    )
    return noStoreJson({ code: 'VALIDATION_FAILED', details }, 400)
  }
  console.error(`${label} upstream failure`, { status })
  return noStoreJson({ code: 'UNAVAILABLE' }, 502)
}

/** O corpo do feedback é minúsculo: `{"value": "USEFUL" | "NOT_USEFUL" | null}`. */
const MAX_BODY_BYTES = 256

export async function readFeedbackBody(
  request: NextRequest,
): Promise<{ body: components['schemas']['InsightFeedbackRequest'] } | { response: NextResponse }> {
  const length = Number(request.headers.get('content-length') ?? '0')
  if (length > MAX_BODY_BYTES) return { response: noStoreJson({ code: 'INVALID_REQUEST' }, 400) }
  const raw = (await request.json().catch(() => undefined)) as { value?: unknown } | undefined
  if (!raw || typeof raw !== 'object' || !('value' in raw)) {
    return { response: noStoreJson({ code: 'INVALID_REQUEST' }, 400) }
  }
  const { value } = raw
  if (value !== null && value !== 'USEFUL' && value !== 'NOT_USEFUL') {
    return { response: noStoreJson({ code: 'VALIDATION_FAILED', details: [{ field: 'value', code: 'INVALID' }] }, 400) }
  }
  return { body: { value } }
}
