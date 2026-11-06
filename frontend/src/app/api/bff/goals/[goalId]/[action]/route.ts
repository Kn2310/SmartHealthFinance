import type { NextRequest } from 'next/server'
import { backendClient } from '@/server/backend'
import { isUuid, rejectCrossOrigin, withSession } from '@/server/bff'
import { goalFailure, goalNotFound, isGoalAction } from '@/server/goals'
import { noStoreJson } from '@/server/http'

export const dynamic = 'force-dynamic'

const PATHS = {
  pause: '/api/v1/workspaces/{workspaceId}/goals/{goalId}/pause',
  resume: '/api/v1/workspaces/{workspaceId}/goals/{goalId}/resume',
  complete: '/api/v1/workspaces/{workspaceId}/goals/{goalId}/complete',
  archive: '/api/v1/workspaces/{workspaceId}/goals/{goalId}/archive',
} as const

/** Pausar, retomar, concluir e arquivar (ADR-0035 §1), idempotentes. Ação desconhecida = rota inexistente. */
export async function POST(request: NextRequest, { params }: { params: Promise<{ goalId: string; action: string }> }) {
  const forbidden = rejectCrossOrigin(request)
  if (forbidden) return forbidden
  const { goalId, action } = await params
  if (!isGoalAction(action)) return noStoreJson({ code: 'NOT_FOUND' }, 404)
  if (!isUuid(goalId)) return goalNotFound()
  return withSession(`goal ${action}`, async (session) => {
    const { data, error, response } = await backendClient(session.accessToken).POST(PATHS[action], {
      params: { path: { workspaceId: session.workspaceId, goalId } },
    })
    if (data) return noStoreJson(data)
    return goalFailure(`goal ${action}`, response.status, error)
  })
}
