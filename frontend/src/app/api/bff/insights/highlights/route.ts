import { backendClient } from '@/server/backend'
import { withSession } from '@/server/bff'
import { noStoreJson } from '@/server/http'
import { insightFailure } from '@/server/insights'

export const dynamic = 'force-dynamic'

/** Destaques da Home (ADR-0023 §5): "Merece atenção", "O que mudou?" e a contagem de Importantes. */
export async function GET() {
  return withSession('insights highlights', async (session) => {
    const { data, error, response } = await backendClient(session.accessToken).GET(
      '/api/v1/workspaces/{workspaceId}/insights/highlights',
      { params: { path: { workspaceId: session.workspaceId } } },
    )
    if (data) return noStoreJson(data)
    return insightFailure('insights highlights', response.status, error)
  })
}
