import type { NextRequest } from 'next/server'
import { backendClient } from '@/server/backend'
import { withSession } from '@/server/bff'
import { noStoreJson } from '@/server/http'
import { insightFailure, parseInsightListQuery } from '@/server/insights'

export const dynamic = 'force-dynamic'

/**
 * Lista de insights (ADR-0023 §1) do Workspace da sessão: filtros, contagens dos chips e página. O corpo é o
 * contrato do backend; nada é recalculado aqui.
 */
export async function GET(request: NextRequest) {
  const query = parseInsightListQuery(request.nextUrl.searchParams)
  return withSession('insights list', async (session) => {
    const { data, error, response } = await backendClient(session.accessToken).GET(
      '/api/v1/workspaces/{workspaceId}/insights',
      { params: { path: { workspaceId: session.workspaceId }, query } },
    )
    if (data) return noStoreJson(data)
    return insightFailure('insights list', response.status, error)
  })
}
