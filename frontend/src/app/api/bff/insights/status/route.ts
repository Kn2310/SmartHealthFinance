import { backendClient } from '@/server/backend'
import { withSession } from '@/server/bff'
import { noStoreJson } from '@/server/http'
import { insightFailure } from '@/server/insights'

export const dynamic = 'force-dynamic'

/** Freshness dos insights (ADR-0022 §8). */
export async function GET() {
  return withSession('insights status', async (session) => {
    const { data, error, response } = await backendClient(session.accessToken).GET(
      '/api/v1/workspaces/{workspaceId}/insights/status',
      { params: { path: { workspaceId: session.workspaceId } } },
    )
    if (data) return noStoreJson(data)
    return insightFailure('insights status', response.status, error)
  })
}
