import 'server-only'
import type { NextRequest, NextResponse } from 'next/server'
import type { components } from '@/lib/api/schema'
import { isUuid, stableCode } from './bff'
import { noStoreJson } from './http'

type ApiError = components['schemas']['ApiError']

/** Leituras do Forecast servidas por `/api/bff/forecast/{view}` (ADR-0032 §1). */
export const FORECAST_VIEWS = ['month', 'next-income', 'upcoming', 'invoices', 'cash-flow', 'status'] as const
export type ForecastView = (typeof FORECAST_VIEWS)[number]

export function isForecastView(value: string): value is ForecastView {
  return (FORECAST_VIEWS as readonly string[]).includes(value)
}

/** Recorrências: dois recursos irmãos, mesmo formato (ADR-0032 §2). */
export type RecurringKind = 'expenses' | 'incomes'
export type RecurringAction = 'confirm' | 'ignore'
export const SERIES_STATUSES = ['DETECTED', 'CONFIRMED', 'IGNORED'] as const
export type SeriesStatus = (typeof SERIES_STATUSES)[number]

/** Só um status conhecido vira filtro; o resto é descartado e cai no padrão do backend (todas as séries). */
export function parseSeriesStatus(value: string | null): SeriesStatus | undefined {
  return value !== null && (SERIES_STATUSES as readonly string[]).includes(value) ? (value as SeriesStatus) : undefined
}

const FIELDS = new Set(['cardId', 'status', 'seriesId', 'expectedAmount', 'expectedDay'])
const NOT_FOUND = new Set(['CARD_NOT_FOUND', 'RECURRING_SERIES_NOT_FOUND'])

/** Falhas do backend → códigos estáveis; só os campos conhecidos do Forecast chegam ao browser. */
export function forecastFailure(label: string, status: number, error: ApiError | undefined): NextResponse {
  if (status === 401) return noStoreJson({ code: 'UNAUTHENTICATED' }, 401)
  const code = stableCode(error?.code)
  if (status === 404 && code && NOT_FOUND.has(code)) return noStoreJson({ code }, 404)
  if (status === 400 && code === 'VALIDATION_FAILED') {
    const details = (error?.details ?? []).flatMap((detail) =>
      detail.field && FIELDS.has(detail.field) ? [{ field: detail.field, code: stableCode(detail.code) ?? 'INVALID' }] : [],
    )
    return noStoreJson({ code: 'VALIDATION_FAILED', details }, 400)
  }
  console.error(`${label} upstream failure`, { status })
  return noStoreJson({ code: 'UNAVAILABLE' }, 502)
}

export const seriesNotFound = () => noStoreJson({ code: 'RECURRING_SERIES_NOT_FOUND' }, 404)
export const invalidSeriesId = (seriesId: string) => !isUuid(seriesId)

/** O corpo do ajuste é minúsculo: `{expectedAmount?: {amount, currency} | null, expectedDay?: 1..31 | null}`. */
const MAX_BODY_BYTES = 512
const AMOUNT = /^\d{1,15}(\.\d{1,2})?$/
const CURRENCY = /^[A-Z]{3}$/

const invalid = (field?: string): { response: NextResponse } => ({
  response: field
    ? noStoreJson({ code: 'VALIDATION_FAILED', details: [{ field, code: 'INVALID' }] }, 400)
    : noStoreJson({ code: 'INVALID_REQUEST' }, 400),
})

export async function readAdjustmentBody(
  request: NextRequest,
): Promise<{ body: components['schemas']['ForecastAdjustmentRequest'] } | { response: NextResponse }> {
  const length = Number(request.headers.get('content-length') ?? '0')
  if (length > MAX_BODY_BYTES) return invalid()
  const raw = (await request.json().catch(() => undefined)) as Record<string, unknown> | undefined
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return invalid()

  const body: components['schemas']['ForecastAdjustmentRequest'] = {}
  if ('expectedAmount' in raw && raw.expectedAmount !== null) {
    const money = raw.expectedAmount as { amount?: unknown; currency?: unknown } | undefined
    if (
      !money || typeof money !== 'object' ||
      typeof money.amount !== 'string' || !AMOUNT.test(money.amount) ||
      typeof money.currency !== 'string' || !CURRENCY.test(money.currency)
    ) {
      return invalid('expectedAmount')
    }
    body.expectedAmount = { amount: money.amount, currency: money.currency }
  }
  if ('expectedDay' in raw && raw.expectedDay !== null) {
    const day = raw.expectedDay
    if (typeof day !== 'number' || !Number.isInteger(day) || day < 1 || day > 31) return invalid('expectedDay')
    body.expectedDay = day
  }
  if (body.expectedAmount === undefined && body.expectedDay === undefined) return invalid()
  return { body }
}
