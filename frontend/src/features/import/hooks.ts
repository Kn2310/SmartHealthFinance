'use client'

import { useEffect, useState } from 'react'
import { useKeyed, type Loadable } from '@/lib/use-keyed'
import {
  fetchAccounts,
  fetchImport,
  fetchRecords,
  type Account,
  type ImportBatch,
  type ImportRecordPage,
  type RecordStatus,
} from './api'

/** Intervalo do polling enquanto o worker processa (ADR-0009: status por GET). */
export const POLL_INTERVAL_MS = 1500

export type { Loadable }

/** O worker ainda vai mudar o status: continuar consultando. */
export const isProcessing = (batch: ImportBatch) => batch.status === 'CONFIRMED' || batch.status === 'PROCESSING'

export function useAccounts() {
  const [attempt, setAttempt] = useState(0)
  const { value } = useKeyed<Account[]>(`accounts#${attempt}`, async () => {
    const result = await fetchAccounts()
    return result.ok ? { status: 'success', data: result.data.items } : { status: 'error', failure: result }
  })
  return { ...value, retry: () => setAttempt((n) => n + 1) }
}

/**
 * Batch de importação. Enquanto o worker processa, consulta de novo a cada {@link POLL_INTERVAL_MS}; para
 * sozinho num status final. `replace` aplica na hora a resposta de confirmar/cancelar.
 */
export function useImportBatch(id: string) {
  const [attempt, setAttempt] = useState(0)
  const [tick, setTick] = useState(0)
  const { value, previous, replace } = useKeyed<ImportBatch>(`${id}#${attempt}#${tick}`, async () => {
    const result = await fetchImport(id)
    return result.ok ? { status: 'success', data: result.data } : { status: 'error', failure: result }
  })

  const polling = value.status === 'success' && isProcessing(value.data)
  useEffect(() => {
    if (!polling) return
    const timer = setTimeout(() => setTick((n) => n + 1), POLL_INTERVAL_MS)
    return () => clearTimeout(timer)
  }, [polling, value])

  // Durante o polling, a próxima consulta não volta a mostrar "carregando": mantém o último batch conhecido.
  const shown: Loadable<ImportBatch> =
    value.status === 'loading' && previous?.status === 'success' && previous.data.id === id && isProcessing(previous.data)
      ? previous
      : value

  return { ...shown, replace, retry: () => setAttempt((n) => n + 1) }
}

export function useImportRecords(id: string, status: RecordStatus | null, page: number, version: string) {
  const [attempt, setAttempt] = useState(0)
  const { value } = useKeyed<ImportRecordPage>(`${id}#${status}#${page}#${version}#${attempt}`, async () => {
    const result = await fetchRecords(id, status, page)
    return result.ok ? { status: 'success', data: result.data } : { status: 'error', failure: result }
  })
  return { ...value, retry: () => setAttempt((n) => n + 1) }
}
