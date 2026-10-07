'use client'

import { useCallback, useEffect, useState } from 'react'
import type { Overview } from '@/lib/api/types'
import { toSearchParams, type OverviewQuery } from '@/lib/overview-query'

export type OverviewErrorKind = 'unavailable' | 'invalid-period'

export type OverviewResult =
  | { status: 'loading' }
  | { status: 'error'; kind: OverviewErrorKind }
  | { status: 'success'; data: Overview }

type Settled = Exclude<OverviewResult, { status: 'loading' }>

/**
 * Uma única chamada ao BFF (`/api/bff/overview`) alimenta a Home inteira. O estado "carregando" é derivado
 * (resultado de outra chave de requisição), sem setState síncrono em effect.
 */
export function useOverview(query: OverviewQuery): OverviewResult & { retry: () => void } {
  const search = toSearchParams(query).toString()
  const [attempt, setAttempt] = useState(0)
  const key = `${search}#${attempt}`
  const [settled, setSettled] = useState<{ key: string; result: Settled } | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    const settle = (result: Settled) => {
      if (!controller.signal.aborted) setSettled({ key, result })
    }

    fetch(`/api/bff/overview${search ? `?${search}` : ''}`, {
      signal: controller.signal,
      headers: { Accept: 'application/json' },
      cache: 'no-store',
    })
      .then(async (response) => {
        if (response.status === 401) {
          // Sessão ausente/expirada: volta ao fluxo de login do BFF. Navegação completa de propósito:
          // /auth/login é um Route Handler que redireciona ao IdP.
          // eslint-disable-next-line @next/next/no-location-assign-relative-destination
          window.location.assign('/auth/login')
          return
        }
        if (response.status === 400) return settle({ status: 'error', kind: 'invalid-period' })
        if (!response.ok) return settle({ status: 'error', kind: 'unavailable' })
        settle({ status: 'success', data: (await response.json()) as Overview })
      })
      .catch(() => settle({ status: 'error', kind: 'unavailable' }))

    return () => controller.abort()
  }, [key, search])

  const retry = useCallback(() => setAttempt((n) => n + 1), [])
  const result: OverviewResult = settled?.key === key ? settled.result : { status: 'loading' }
  return { ...result, retry }
}
