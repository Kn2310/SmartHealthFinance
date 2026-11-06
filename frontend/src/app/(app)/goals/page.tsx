import type { Metadata } from 'next'
import { GoalsView, type GoalsFilter } from '@/features/goals/GoalsView'
import { isUuid } from '@/lib/uuid'

export const metadata: Metadata = { title: 'Objetivos' }

const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/

/**
 * D-Goals / D-GoalCreated (ADR-0037). `?created=` (id) e `?first=` (mês) só montam o toast; `?status=` filtra
 * concluídos ou arquivados. Valores fora do formato são ignorados.
 */
export default async function GoalsPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const { created, first, status } = await searchParams
  const filter: GoalsFilter = status === 'COMPLETED' || status === 'ARCHIVED' ? status : 'OPEN'
  return (
    <GoalsView
      filter={filter}
      created={isUuid(created) ? created : null}
      firstMonth={typeof first === 'string' && MONTH.test(first) ? first : null}
    />
  )
}
