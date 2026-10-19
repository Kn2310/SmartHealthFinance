import 'server-only'
import type { NextRequest } from 'next/server'
import { backendClient } from './backend'
import { isUuid, rejectCrossOrigin, withSession } from './bff'
import { noStoreJson } from './http'
import { insightFailure, readFeedbackBody } from './insights'

export type InsightAction = 'seen' | 'dismiss' | 'feedback'

/**
 * Ações do usuário sobre um insight (ADR-0023 §3), sempre no Workspace da sessão e só da mesma origem. O backend é
 * idempotente pelo estado; a resposta é o item atualizado.
 */
export async function insightAction(request: NextRequest, params: Promise<{ insightId: string }>,
  action: InsightAction) {
  const forbidden = rejectCrossOrigin(request)
  if (forbidden) return forbidden
  const { insightId } = await params
  if (!isUuid(insightId)) return noStoreJson({ code: 'INSIGHT_NOT_FOUND' }, 404)
  const label = `insight ${action}`

  return withSession(label, async (session) => {
    const client = backendClient(session.accessToken)
    const path = { workspaceId: session.workspaceId, insightId }
    if (action === 'feedback') {
      const parsed = await readFeedbackBody(request)
      if ('response' in parsed) return parsed.response
      const { data, error, response } = await client.PUT(
        '/api/v1/workspaces/{workspaceId}/insights/{insightId}/feedback',
        { params: { path }, body: parsed.body },
      )
      return data ? noStoreJson(data) : insightFailure(label, response.status, error)
    }
    const { data, error, response } =
      action === 'seen'
        ? await client.POST('/api/v1/workspaces/{workspaceId}/insights/{insightId}/seen', { params: { path } })
        : await client.POST('/api/v1/workspaces/{workspaceId}/insights/{insightId}/dismiss', { params: { path } })
    return data ? noStoreJson(data) : insightFailure(label, response.status, error)
  })
}
