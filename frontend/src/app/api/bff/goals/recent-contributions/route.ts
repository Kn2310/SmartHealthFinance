import type { NextRequest } from 'next/server'
import { backendClient } from '@/server/backend'
import { withSession } from '@/server/bff'
import { goalFailure, intParam } from '@/server/goals'
import { noStoreJson } from '@/server/http'

export const dynamic = 'force-dynamic'

/** "Aportes recentes" do D-Goals (ADR-0035 §1); `limit` de 1 a 20, senão o padrão do backend (5). */
export async function GET(request: NextRequest) {
  const limit = intParam(request.nextUrl.searchParams.get('limit'), 1, 20)
  return withSession('goal recent contributions', async (session) => {
    const { data, error, response } = await backendClient(session.accessToken).GET(
      '/api/v1/workspaces/{workspaceId}/goals/recent-contributions',
      { params: { path: { workspaceId: session.workspaceId }, query: limit ? { limit } : {} } },
    )
    if (data) return noStoreJson(data)
    return goalFailure('goal recent contributions', response.status, error)
  })
}
