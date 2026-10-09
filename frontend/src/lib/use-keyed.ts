'use client'

import { useCallback, useEffect, useState } from 'react'
import type { Failure } from './bff-client'

export type Loadable<T> = { status: 'loading' } | { status: 'error'; failure: Failure } | { status: 'success'; data: T }

/**
 * Resultado derivado por chave de requisição (mesmo padrão do `useOverview`): trocar a chave volta a
 * "carregando" sem setState síncrono dentro do effect.
 */
export function useKeyed<T>(key: string, load: (signal: { aborted: boolean }) => Promise<Loadable<T> | null>) {
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
