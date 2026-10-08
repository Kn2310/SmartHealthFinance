import 'server-only'
import type { NextResponse } from 'next/server'
import type { components } from '@/lib/api/schema'
import { stableCode } from './bff'
import { noStoreJson } from './http'

type ApiError = components['schemas']['ApiError']

/** Mesmos limites do backend (ADR-0009 §3): o BFF recusa antes de repassar. */
export const MAX_IMPORT_FILE_BYTES = 10 * 1024 * 1024

/** Mesmo formato aceito pelo backend para `Idempotency-Key` (ADR-0005). */
export const IDEMPOTENCY_KEY = /^[A-Za-z0-9._~:-]{1,100}$/

/**
 * Erros da API de importação → códigos do BFF. Só passam códigos estáveis do contrato; mensagens, `traceId` e
 * IDs do backend nunca chegam ao browser nem aos logs.
 */
export function importFailure(label: string, status: number, error: ApiError | undefined): NextResponse {
  const code = stableCode(error?.code)
  if (status === 401) return noStoreJson({ code: 'UNAUTHENTICATED' }, 401)
  if (status === 413) return noStoreJson({ code: 'FILE_TOO_LARGE' }, 413)
  if (status === 422 && code === 'IMPORT_FILE_REJECTED') {
    return noStoreJson({ code: 'FILE_REJECTED', reason: stableCode(error?.details?.[0]?.code) ?? 'UNKNOWN' }, 422)
  }
  if (status === 422 && code === 'IDEMPOTENCY_KEY_REUSED') return noStoreJson({ code }, 422)
  if (status === 404 && (code === 'IMPORT_NOT_FOUND' || code === 'ACCOUNT_NOT_FOUND')) {
    return noStoreJson({ code }, 404)
  }
  if (status === 409 && (code === 'IMPORT_STATUS_CONFLICT' || code === 'ACCOUNT_ARCHIVED')) {
    return noStoreJson({ code }, 409)
  }
  if (status === 400) return noStoreJson({ code: 'INVALID_REQUEST' }, 400)
  console.error(`${label} upstream failure`, { status })
  return noStoreJson({ code: 'UNAVAILABLE' }, 502)
}
