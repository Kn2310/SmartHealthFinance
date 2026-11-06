import type { NextRequest } from 'next/server'
import { backendClient } from '@/server/backend'
import { isUuid, rejectCrossOrigin, withSession } from '@/server/bff'
import { goalFailure, goalNotFound, readSimulationBody } from '@/server/goals'
import { noStoreJson } from '@/server/http'

export const dynamic = 'force-dynamic'

/** Simulation Card (ADR-0036 §2): até 5 cenários, sempre Simulado. Nada é gravado; é POST, então exige a mesma origem. */
export async function POST(request: NextRequest, { params }: { params: Promise<{ goalId: string }> }) {
  const forbidden = rejectCrossOrigin(request)
  if (forbidden) return forbidden
  const { goalId } = await params
  if (!isUuid(goalId)) return goalNotFound()
  return withSession('goal simulation', async (session) => {
    const parsed = await readSimulationBody(request)
    if ('response' in parsed) return parsed.response
    const { data, error, response } = await backendClient(session.accessToken).POST(
      '/api/v1/workspaces/{workspaceId}/goals/{goalId}/simulations',
      { params: { path: { workspaceId: session.workspaceId, goalId } }, body: parsed.body },
    )
    if (data) return noStoreJson(data)
    return goalFailure('goal simulation', response.status, error)
  })
}
