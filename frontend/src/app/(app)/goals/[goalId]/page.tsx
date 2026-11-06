import type { Metadata } from 'next'
import { GoalDetailView } from '@/features/goals/GoalDetailView'
import { isUuid } from '@/lib/uuid'

export const metadata: Metadata = { title: 'Objetivo' }

/** D-GoalDetail. Id que não é UUID nem chega ao BFF: é o mesmo "não encontrado" de outro Workspace. */
export default async function GoalPage({ params }: { params: Promise<{ goalId: string }> }) {
  const { goalId } = await params
  return <GoalDetailView id={isUuid(goalId) ? goalId : null} />
}
