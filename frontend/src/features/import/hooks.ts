'use client'

import { useCallback, useEffect, useState } from 'react'
import {
  fetchAccounts,
  fetchImport,
  fetchRecords,
  type Account,
  type Failure,
  type ImportBatch,
  type ImportRecordPage,
  type RecordStatus,
} from './api'

/** Intervalo do polling enquanto o worker processa (ADR-0009: status por GET). */
export const POLL_INTERVAL_MS = 1500

export type Loadable<T> = { status: 'loading' } | { status: 'error'; failure: Failure } | { status: 'success'; data: T }

/** O worker ainda vai mudar o status: continuar consultando. */
export const isProcessing = (batch: ImportBatch) => batch.status === 'CONFIRMED' || batch.status === 'PROCESSING'

/**
 * Resultado derivado por chave de requisição (mesmo padrão do `useOverview`): trocar a chave volta a
 * "carregando" sem setState síncrono dentro do effect.
 */
function useKeyed<T>(key: string, load: (signal: { aborted: boolean }) => Promise<Loadable<T> | null>) {
  const [settled, setSettled] = useState<{ key: string; value: Loadable<T> } | null>(null)

  useEffect(() => {
    const signal = { aborted: false }
    load(signal).then((value) => {
      if (value && !signal.aborted) setSettled({ key, value })
    })
    return () => {
      signal.aborted = true
    }
    // `load` muda junto com `key` (os parâmetros da requisição estão na chave).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key])

  const value: Loadable<T> = settled?.key === key ? settled.value : { status: 'loading' }
  const replace = useCallback((data: T) => setSettled({ key, value: { status: 'success', data } }), [key])
  /** Último resultado resolvido, de qualquer chave. */
  const previous = settled?.value ?? null
  return { value, previous, replace }
}

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
