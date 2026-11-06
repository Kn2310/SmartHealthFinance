import type { Metadata } from 'next'
import { EditGoalView } from '@/features/goals/EditGoalView'
import { isUuid } from '@/lib/uuid'

export const metadata: Metadata = { title: 'Editar objetivo' }

/** Edição e status do objetivo (ADR-0037). */
export default async function EditGoalPage({ params }: { params: Promise<{ goalId: string }> }) {
  const { goalId } = await params
  return <EditGoalView id={isUuid(goalId) ? goalId : null} />
}
