import type { Metadata } from 'next'
import { NewGoalView, type GoalOriginParam } from '@/features/goals/NewGoalView'
import type { GoalType } from '@/features/goals/api'
import { isUuid } from '@/lib/uuid'

export const metadata: Metadata = { title: 'Criar objetivo' }

const TYPES: GoalType[] = ['EMERGENCY_RESERVE', 'PURCHASE', 'CUSTOM']

/**
 * D-CreateGoal (ADR-0037). A URL traz só a origem (`origin=INSIGHT&insightId=` ou `origin=HEALTH`) e o tipo; a sugestão
 * do insight é lida do backend, nunca da URL.
 */
export default async function NewGoalPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const { type, origin, insightId } = await searchParams
  const parsedOrigin: GoalOriginParam =
    origin === 'INSIGHT' && isUuid(insightId) ? { kind: 'INSIGHT', insightId }
      : origin === 'HEALTH' ? { kind: 'HEALTH' } : { kind: 'MANUAL' }
  return <NewGoalView type={TYPES.includes(type as GoalType) ? (type as GoalType) : null} origin={parsedOrigin} />
}
