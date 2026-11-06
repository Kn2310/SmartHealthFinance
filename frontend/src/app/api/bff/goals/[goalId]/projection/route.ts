import type { NextRequest } from 'next/server'
import { backendClient } from '@/server/backend'
import { isUuid, withSession } from '@/server/bff'
import { goalFailure, goalNotFound } from '@/server/goals'
import { noStoreJson } from '@/server/http'

export const dynamic = 'force-dynamic'

/** Gráfico do D-GoalDetail (ADR-0036 §5): guardado (fato), projeção (Estimado) e prazo, prontos do backend. */
export async function GET(_request: NextRequest, { params }: { params: Promise<{ goalId: string }> }) {
  const { goalId } = await params
  if (!isUuid(goalId)) return goalNotFound()
  return withSession('goal projection', async (session) => {
    const { data, error, response } = await backendClient(session.accessToken).GET(
      '/api/v1/workspaces/{workspaceId}/goals/{goalId}/projection',
      { params: { path: { workspaceId: session.workspaceId, goalId } } },
    )
    if (data) return noStoreJson(data)
    return goalFailure('goal projection', response.status, error)
  })
}
