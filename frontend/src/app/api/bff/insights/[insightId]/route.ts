import { backendClient } from '@/server/backend'
import { isUuid, withSession } from '@/server/bff'
import { noStoreJson } from '@/server/http'
import { insightFailure } from '@/server/insights'

export const dynamic = 'force-dynamic'

type Context = { params: Promise<{ insightId: string }> }

/** Detalhe de um insight em qualquer estado (ADR-0023 §1). Só lê: o "visto" é um POST à parte. */
export async function GET(_request: Request, { params }: Context) {
  const { insightId } = await params
  if (!isUuid(insightId)) return noStoreJson({ code: 'INSIGHT_NOT_FOUND' }, 404)
  return withSession('insight detail', async (session) => {
    const { data, error, response } = await backendClient(session.accessToken).GET(
      '/api/v1/workspaces/{workspaceId}/insights/{insightId}',
      { params: { path: { workspaceId: session.workspaceId, insightId } } },
    )
    if (data) return noStoreJson(data)
    return insightFailure('insight detail', response.status, error)
  })
}
