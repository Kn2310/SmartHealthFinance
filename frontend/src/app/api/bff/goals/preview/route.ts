import type { NextRequest } from 'next/server'
import { backendClient } from '@/server/backend'
import { rejectCrossOrigin, withSession } from '@/server/bff'
import { goalFailure, readPreviewBody } from '@/server/goals'
import { noStoreJson } from '@/server/http'

export const dynamic = 'force-dynamic'

/**
 * Prévia do D-CreateGoal (ADR-0036 §3): "Já guardado" (fato), alvo sugerido da reserva e o cenário Simulado. Nada é
 * gravado, mas é um POST: exige a mesma origem como as demais mutações.
 */
export async function POST(request: NextRequest) {
  const forbidden = rejectCrossOrigin(request)
  if (forbidden) return forbidden
  return withSession('goal preview', async (session) => {
    const parsed = await readPreviewBody(request)
    if ('response' in parsed) return parsed.response
    const { data, error, response } = await backendClient(session.accessToken).POST(
      '/api/v1/workspaces/{workspaceId}/goals/simulations/preview',
      { params: { path: { workspaceId: session.workspaceId } }, body: parsed.body },
    )
    if (data) return noStoreJson(data)
    return goalFailure('goal preview', response.status, error)
  })
}
