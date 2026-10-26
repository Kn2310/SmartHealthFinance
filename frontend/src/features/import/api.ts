'use client'

import type { components } from '@/lib/api/schema'
import { bffCall as call } from '@/lib/bff-client'

type Schemas = components['schemas']
export type ImportBatch = Schemas['ImportResponse']
export type ImportRecord = Schemas['ImportRecordResponse']
export type ImportRecordPage = Schemas['ImportRecordPageResponse']
export type Account = Schemas['AccountResponse']
export type Card = Schemas['CardResponse']
export type RecordStatus = ImportRecord['status']

export type { Failure, Result } from '@/lib/bff-client'

/** Chamadas ao BFF da importação (`bffCall`: 401 volta ao login, falhas viram códigos estáveis). */
export const fetchAccounts = () => call<Schemas['AccountListResponse']>('/api/bff/accounts')
export const fetchCards = () => call<Schemas['CardListResponse']>('/api/bff/cards')

/** Destino do arquivo: extrato de conta ou fatura de cartão (ADR-0009 §19). */
export type Destination = { kind: 'account' | 'card'; id: string }

export function uploadStatement(file: File, destination: Destination, idempotencyKey: string) {
  const form = new FormData()
  form.append('file', file, file.name)
  form.append(destination.kind === 'card' ? 'cardId' : 'accountId', destination.id)
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
