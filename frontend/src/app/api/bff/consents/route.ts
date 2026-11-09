import { backendClient } from '@/server/backend'
import { withSession } from '@/server/bff'
import { consentFailure } from '@/server/consents'
import { noStoreJson } from '@/server/http'

export const dynamic = 'force-dynamic'

/** Consentimentos da pessoa no Workspace da sessão (ADR-0038 §7). */
export async function GET() {
  return withSession('consents', async (session) => {
    const { data, error, response } = await backendClient(session.accessToken).GET(
      '/api/v1/workspaces/{workspaceId}/consents',
      { params: { path: { workspaceId: session.workspaceId } } },
    )
    if (data) return noStoreJson(data)
    return consentFailure('consents', response.status, error)
  })
}
