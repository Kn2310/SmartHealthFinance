import type { NextRequest } from 'next/server'
import { backendClient } from '@/server/backend'
import { withSession } from '@/server/bff'
import { noStoreJson } from '@/server/http'
import { insightFailure, parseRelatedQuery } from '@/server/insights'

export const dynamic = 'force-dynamic'

/** Até 3 insights ativos de uma transação ou de uma dimensão do Health (ADR-0023 §1). */
export async function GET(request: NextRequest) {
  const query = parseRelatedQuery(request.nextUrl.searchParams)
  return withSession('insights related', async (session) => {
    const { data, error, response } = await backendClient(session.accessToken).GET(
      '/api/v1/workspaces/{workspaceId}/insights/related',
      { params: { path: { workspaceId: session.workspaceId }, query } },
    )
    if (data) return noStoreJson(data)
    return insightFailure('insights related', response.status, error)
  })
}
