'use client'

import type { components } from '@/lib/api/schema'

type Schemas = components['schemas']
export type ImportBatch = Schemas['ImportResponse']
export type ImportRecord = Schemas['ImportRecordResponse']
export type ImportRecordPage = Schemas['ImportRecordPageResponse']
export type Account = Schemas['AccountResponse']
export type RecordStatus = ImportRecord['status']

/** Falha do BFF já traduzida em código estável; `reason` só vem em `FILE_REJECTED`. */
export interface Failure {
  ok: false
  code: string
  reason?: string
}
export type Result<T> = { ok: true; status: number; data: T } | Failure

const JSON_ACCEPT = { Accept: 'application/json' }

/**
 * Chamadas ao BFF da importação. Sessão expirada (401) volta ao login; qualquer outra falha vira um código
 * estável — nunca uma mensagem do servidor.
 */
async function call<T>(input: string, init: RequestInit = {}): Promise<Result<T>> {
  try {
    const response = await fetch(input, { ...init, cache: 'no-store', headers: { ...JSON_ACCEPT, ...init.headers } })
    if (response.status === 401) {
      // Navegação completa de propósito: /auth/login é um Route Handler que redireciona ao IdP.
      // eslint-disable-next-line @next/next/no-location-assign-relative-destination
      window.location.assign('/auth/login')
      return { ok: false, code: 'UNAUTHENTICATED' }
    }
    const body = (await response.json().catch(() => null)) as unknown
    if (response.ok && body) return { ok: true, status: response.status, data: body as T }
    const failure = (body ?? {}) as { code?: unknown; reason?: unknown }
    return {
      ok: false,
      code: typeof failure.code === 'string' ? failure.code : 'UNAVAILABLE',
      reason: typeof failure.reason === 'string' ? failure.reason : undefined,
    }
  } catch {
    return { ok: false, code: 'UNAVAILABLE' }
  }
}

export const fetchAccounts = () => call<Schemas['AccountListResponse']>('/api/bff/accounts')

export function uploadStatement(file: File, accountId: string, idempotencyKey: string) {
  const form = new FormData()
  form.append('file', file, file.name)
  form.append('accountId', accountId)
  return call<ImportBatch>('/api/bff/imports', {
    method: 'POST',
    body: form,
    headers: { 'Idempotency-Key': idempotencyKey },
  })
}

export const fetchImport = (id: string) => call<ImportBatch>(`/api/bff/imports/${encodeURIComponent(id)}`)

export function fetchRecords(id: string, status: RecordStatus | null, page: number) {
  const search = new URLSearchParams({ page: String(page) })
  if (status) search.set('status', status)
  return call<ImportRecordPage>(`/api/bff/imports/${encodeURIComponent(id)}/records?${search}`)
}

export const confirmImport = (id: string) =>
  call<ImportBatch>(`/api/bff/imports/${encodeURIComponent(id)}/confirm`, { method: 'POST' })

export const cancelImport = (id: string) =>
  call<ImportBatch>(`/api/bff/imports/${encodeURIComponent(id)}/cancel`, { method: 'POST' })
