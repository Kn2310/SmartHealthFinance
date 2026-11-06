import type { NextRequest } from 'next/server'
import { backendClient } from '@/server/backend'
import { isUuid, rejectCrossOrigin, withSession } from '@/server/bff'
import { goalFailure, goalNotFound, readPlanBody } from '@/server/goals'
import { noStoreJson } from '@/server/http'

export const dynamic = 'force-dynamic'

/** "Aplicar ao objetivo" (ADR-0034 §5): troca só o aporte planejado e o prazo, depois da confirmação na UI. */
export async function PUT(request: NextRequest, { params }: { params: Promise<{ goalId: string }> }) {
  const forbidden = rejectCrossOrigin(request)
  if (forbidden) return forbidden
  const { goalId } = await params
  if (!isUuid(goalId)) return goalNotFound()
  return withSession('goal plan', async (session) => {
    const parsed = await readPlanBody(request)
    if ('response' in parsed) return parsed.response
    const { data, error, response } = await backendClient(session.accessToken).PUT(
      '/api/v1/workspaces/{workspaceId}/goals/{goalId}/plan',
      { params: { path: { workspaceId: session.workspaceId, goalId } }, body: parsed.body },
    )
    if (data) return noStoreJson(data)
    return goalFailure('goal plan', response.status, error)
  })
}
