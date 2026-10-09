import 'server-only'
import type { NextRequest, NextResponse } from 'next/server'
import { ACCOUNT_NAME_MAX_LENGTH, ACCOUNT_TYPES, INSTITUTION_NAME_MAX_LENGTH } from '@/lib/api/contract'
import type { components } from '@/lib/api/schema'
import { stableCode } from './bff'
import { noStoreJson } from './http'

type Schemas = components['schemas']
type ApiError = Schemas['ApiError']
type AccountType = Schemas['AccountResponse']['type']
export type AccountFields = Schemas['UpdateAccountRequest']

/** O corpo de criar/editar conta tem 4 campos curtos: qualquer coisa muito maior nem é lida. */
const MAX_BODY_BYTES = 4 * 1024

const FIELDS = new Set(['name', 'type', 'institutionName', 'includedInTotal'])
/** Códigos de campo que a UI conhece; os da Bean Validation viram os do domínio. */
const FIELD_CODES: Partial<Record<string, string>> = {
  REQUIRED: 'REQUIRED',
  TOO_LONG: 'TOO_LONG',
  INVALID: 'INVALID',
  INVALID_CHARACTERS: 'INVALID_CHARACTERS',
  NotBlank: 'REQUIRED',
  NotNull: 'REQUIRED',
  Size: 'TOO_LONG',
}

type FieldIssue = { field: string; code: string }

const validationFailed = (details: FieldIssue[]) => noStoreJson({ code: 'VALIDATION_FAILED', details }, 400)

/** Mesma regra do domínio (AccountName/InstitutionName): sem bordas em branco e sem caracteres de controle. */
const CONTROL = /[\u0000-\u001f\u007f-\u009f]/

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

/**
 * Lê e valida o corpo de criar (`includedInTotal` opcional, padrão true) ou editar (substituição completa).
 * Monta um objeto novo só com os campos do contrato: nada além disso chega ao backend. Em caso de recusa,
 * devolve a resposta 400 pronta.
 */
export async function readAccountFields(
  request: NextRequest,
  mode: 'create' | 'update',
): Promise<{ fields: AccountFields } | { response: NextResponse }> {
  const raw = await request.text().catch(() => null)
  if (raw === null || raw.length > MAX_BODY_BYTES) return { response: noStoreJson({ code: 'INVALID_REQUEST' }, 400) }
  let body: unknown
  try {
    body = JSON.parse(raw)
  } catch {
    return { response: noStoreJson({ code: 'INVALID_REQUEST' }, 400) }
  }
  if (typeof body !== 'object' || body === null || Array.isArray(body)) {
    return { response: noStoreJson({ code: 'INVALID_REQUEST' }, 400) }
  }

  const input = body as Record<string, unknown>
  const issues: FieldIssue[] = []
  const name = text(input.name, 'name', ACCOUNT_NAME_MAX_LENGTH, true, issues)
  const institutionName = text(input.institutionName, 'institutionName', INSTITUTION_NAME_MAX_LENGTH, false, issues)

  const type = input.type
  if (type === undefined || type === null || type === '') issues.push({ field: 'type', code: 'REQUIRED' })
  else if (!(ACCOUNT_TYPES as readonly unknown[]).includes(type)) issues.push({ field: 'type', code: 'INVALID' })

  let includedInTotal = input.includedInTotal
  if (includedInTotal === undefined && mode === 'create') includedInTotal = true
  if (includedInTotal === undefined || includedInTotal === null) issues.push({ field: 'includedInTotal', code: 'REQUIRED' })
  else if (typeof includedInTotal !== 'boolean') issues.push({ field: 'includedInTotal', code: 'INVALID' })

  if (issues.length > 0) return { response: validationFailed(issues) }
  return {
    fields: { name: name!, type: type as AccountType, institutionName, includedInTotal: includedInTotal as boolean },
  }
}

/**
 * Erros da API de contas → códigos do BFF. Só passam códigos estáveis do contrato (e, na validação, só `field` e
 * `code` conhecidos); mensagens, `traceId` e IDs do backend nunca chegam ao browser nem aos logs.
 */
export function accountFailure(label: string, status: number, error: ApiError | undefined): NextResponse {
  const code = stableCode(error?.code)
  if (status === 401) return noStoreJson({ code: 'UNAUTHENTICATED' }, 401)
  if (status === 400 && code === 'VALIDATION_FAILED') {
    const details = (error?.details ?? []).flatMap((detail) => {
      const field = detail.field && FIELDS.has(detail.field) ? detail.field : null
      if (!field) return []
      return [{ field, code: (detail.code && FIELD_CODES[detail.code]) ?? 'INVALID' }]
    })
    return validationFailed(details)
  }
  if (status === 400) return noStoreJson({ code: 'INVALID_REQUEST' }, 400)
  if (status === 404 && code === 'ACCOUNT_NOT_FOUND') return noStoreJson({ code }, 404)
  if (status === 409 && (code === 'ACCOUNT_ARCHIVED' || code === 'CONFLICT')) return noStoreJson({ code }, 409)
  console.error(`${label} upstream failure`, { status })
  return noStoreJson({ code: 'UNAVAILABLE' }, 502)
}
