'use client'

import { useState } from 'react'
import type { Health, HealthEvolution, HealthMethod } from '@/lib/api/types'
import { bffCall } from '@/lib/bff-client'
import { useKeyed } from '@/lib/use-keyed'

/** Uma chamada ao BFF por visão (ADR-0019); trocar os parâmetros muda a chave e recarrega. */
function useHealthView<T>(path: string, enabled = true) {
  const [attempt, setAttempt] = useState(0)
  const { value } = useKeyed<T | null>(`${path}#${attempt}#${enabled}`, async () => {
    if (!enabled) return { status: 'success', data: null }
    const result = await bffCall<T>(path)
    return result.ok ? { status: 'success', data: result.data } : { status: 'error', failure: result }
  })
  return { ...value, retry: () => setAttempt((n) => n + 1) }
}

function withSearch(path: string, params: Record<string, string | undefined>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) if (value) search.set(key, value)
  const query = search.toString()
  return query ? `${path}?${query}` : path
}

/** Resumo, dimensões, fatores e próximos passos; sem mês = o corrente. */
export function useHealth(month?: string) {
  return useHealthView<Health>(withSearch('/api/bff/health/summary', { month }))
}

export function useHealthEvolution(month?: string, months?: number) {
  return useHealthView<HealthEvolution>(
    withSearch('/api/bff/health/evolution', { month, months: months ? String(months) : undefined }),
  )
}

/** "Como calculamos?": só carrega quando o drawer abre. */
export function useHealthMethod(enabled: boolean) {
  return useHealthView<HealthMethod>('/api/bff/health/method', enabled)
}
