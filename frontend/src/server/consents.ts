import 'server-only'
import type { NextRequest, NextResponse } from 'next/server'
import { CONSENT_PURPOSES } from '@/lib/api/contract'
import type { components } from '@/lib/api/schema'
import { stableCode } from './bff'
import { noStoreJson } from './http'

type Schemas = components['schemas']
type ApiError = Schemas['ApiError']
export type ChangeConsentFields = Schemas['ChangeConsentRequest']

/** O corpo tem 2 campos curtos: qualquer coisa muito maior nem é lida. */
const MAX_BODY_BYTES = 1024
const TERMS_VERSION = /^[a-z0-9][a-z0-9.-]{0,39}$/

export const isConsentPurpose = (value: string): value is (typeof CONSENT_PURPOSES)[number] =>
  (CONSENT_PURPOSES as readonly string[]).includes(value)

const invalid = () => ({ response: noStoreJson({ code: 'INVALID_REQUEST' }, 400) })

/**
 * Lê `{granted, termsVersion?}` e remonta um objeto novo só com esses campos (ADR-0038 §7). A versão do termo
 * só segue quando é para conceder; revogar nunca leva versão.
 */
export async function readConsentChange(
  request: NextRequest,
): Promise<{ fields: ChangeConsentFields } | { response: NextResponse }> {
  const raw = await request.text().catch(() => null)
  if (raw === null || raw.length > MAX_BODY_BYTES) return invalid()
  let body: unknown
  try {
    body = JSON.parse(raw)
  } catch {
    return invalid()
  }
  if (typeof body !== 'object' || body === null || Array.isArray(body)) return invalid()
  const { granted, termsVersion } = body as Record<string, unknown>
  if (typeof granted !== 'boolean') {
    return { response: noStoreJson({ code: 'VALIDATION_FAILED', details: [{ field: 'granted', code: 'REQUIRED' }] }, 400) }
  }
  if (!granted) return { fields: { granted: false } }
  if (typeof termsVersion !== 'string' || !TERMS_VERSION.test(termsVersion)) {
    return { response: noStoreJson({ code: 'CONSENT_TERMS_OUTDATED' }, 409) }
  }
  return { fields: { granted: true, termsVersion } }
}

const CONFLICTS = new Set(['CONSENT_TERMS_OUTDATED', 'CONSENT_PREREQUISITE_MISSING', 'CONFLICT'])

/** Erros da API de consentimentos → códigos do BFF; mensagens e `traceId` nunca chegam ao browser nem aos logs. */
export function consentFailure(label: string, status: number, error: ApiError | undefined): NextResponse {
  const code = stableCode(error?.code)
  if (status === 401) return noStoreJson({ code: 'UNAUTHENTICATED' }, 401)
  if (status === 400) return noStoreJson({ code: 'INVALID_REQUEST' }, 400)
  if (status === 404 && code === 'CONSENT_PURPOSE_NOT_FOUND') return noStoreJson({ code }, 404)
  if (status === 409 && code && CONFLICTS.has(code)) return noStoreJson({ code }, 409)
  console.error(`${label} upstream failure`, { status })
  return noStoreJson({ code: 'UNAVAILABLE' }, 502)
}
