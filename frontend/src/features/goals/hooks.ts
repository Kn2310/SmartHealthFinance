'use client'

import { useState } from 'react'
import type { InsightDetail } from '@/lib/api/types'
import { bffCall } from '@/lib/bff-client'
import { useKeyed } from '@/lib/use-keyed'
import {
  goalPath,
  simulateGoal,
  type Goal,
  type GoalContributions,
  type GoalList,
  type GoalProjection,
  type RecentContributions,
  type Simulation,
  type SimulationScenarioRequest,
} from './api'

/**
 * Uma chamada ao BFF por chave; trocar os parâmetros muda a chave e recarrega. `null` desliga a chamada. Progresso,
 * previsão, simulação e projeção chegam prontos do backend: aqui nada é somado nem projetado.
 */
function useGoalView<T>(path: string | null) {
  const [attempt, setAttempt] = useState(0)
  const { value, replace } = useKeyed<T | null>(`${path}#${attempt}`, async () => {
    if (!path) return { status: 'success', data: null }
    const result = await bffCall<T>(path)
    return result.ok ? { status: 'success', data: result.data } : { status: 'error', failure: result }
  })
  return { ...value, replace, attempt, retry: () => setAttempt((n) => n + 1) }
}

/** Sem status = os abertos (ACTIVE e PAUSED), com o resumo dos abertos (ADR-0035 §1). */
export const useGoals = (status: 'COMPLETED' | 'ARCHIVED' | null = null) =>
  useGoalView<GoalList>(status ? `/api/bff/goals?status=${status}` : '/api/bff/goals')

export const useRecentContributions = () => useGoalView<RecentContributions>('/api/bff/goals/recent-contributions?limit=5')

export const useGoal = (id: string) => useGoalView<Goal>(goalPath(id))

/** `version` muda a chave depois de um aporte ou de "Aplicar": o gráfico e o histórico são relidos. */
export const useGoalProjection = (id: string, version: number) =>
  useGoalView<GoalProjection>(`${goalPath(id, 'projection')}?v=${version}`)

export const useGoalContributions = (id: string, version: number) =>
  useGoalView<GoalContributions>(`${goalPath(id, 'contributions')}?pageSize=50&v=${version}`)

/**
 * Simulação (sempre Simulado, nunca gravada). Os cenários entram na chave: mudar o valor simula de novo; `null`
 * desliga (ex.: objetivo ainda carregando).
 */
export function useSimulation(id: string | null, scenarios: SimulationScenarioRequest[] | null) {
  const key = id && scenarios ? `${id}:${JSON.stringify(scenarios)}` : 'off'
  const [attempt, setAttempt] = useState(0)
  const { value } = useKeyed<Simulation | null>(`simulation:${key}#${attempt}`, async () => {
    if (!id || !scenarios) return { status: 'success', data: null }
    const result = await simulateGoal(id, scenarios)
    return result.ok ? { status: 'success', data: result.data } : { status: 'error', failure: result }
  })
  return { ...value, retry: () => setAttempt((n) => n + 1) }
}

/** Detalhe do insight de origem (sugestão do CREATE_SAVINGS_GOAL); `null` desliga a chamada. */
export const useInsightForGoal = (insightId: string | null) =>
  useGoalView<InsightDetail>(insightId ? `/api/bff/insights/${encodeURIComponent(insightId)}` : null)

/**
 * Objetivo de reserva aberto, para o passo da reserva do Health (adendo ao ADR-0020): `null` se não existe,
 * `undefined` enquanto carrega, se falhou ou se `enabled` é falso.
 */
export function useReserveGoal(enabled: boolean): Goal | null | undefined {
  const list = useGoalView<GoalList>(enabled ? '/api/bff/goals' : null)
  if (list.status !== 'success' || !list.data) return undefined
  return list.data.goals.find((g) => g.type === 'EMERGENCY_RESERVE') ?? null
}
