import type { NextRequest } from 'next/server'
import { analysisFailure } from '@/server/analytics'
import { backendClient } from '@/server/backend'
import { withSession } from '@/server/bff'
import { isHealthView, parseHealthQuery, type HealthQueryParams, type HealthView } from '@/server/health'
import { noStoreJson } from '@/server/http'

export const dynamic = 'force-dynamic'

type Context = { params: Promise<{ view: string }> }

/**
 * Financial Health (ADR-0019): repassa UMA chamada a `GET /api/v1/workspaces/{id}/health[/view]` do Workspace da
 * sessão. O corpo de sucesso é o contrato do backend, sem recálculo: o score e os textos vêm prontos do snapshot.
 */
export async function GET(request: NextRequest, { params }: Context) {
  const { view } = await params
  if (!isHealthView(view)) return noStoreJson({ code: 'NOT_FOUND' }, 404)
  const query = parseHealthQuery(request.nextUrl.searchParams)

  return withSession(`health ${view}`, async (session) => {
    const { data, error, response } = await call(session.accessToken, session.workspaceId, view, query)
    if (data) return noStoreJson(data)
    // Mesma tradução de falhas da Análise: só `month` e `months` chegam ao browser.
    return analysisFailure(`health ${view}`, response.status, error)
  })
}

function call(token: string, workspaceId: string, view: HealthView, query: HealthQueryParams) {
  const client = backendClient(token)
  const path = { workspaceId }
  switch (view) {
    case 'summary':
      return client.GET('/api/v1/workspaces/{workspaceId}/health', { params: { path, query: { month: query.month } } })
    case 'evolution':
      return client.GET('/api/v1/workspaces/{workspaceId}/health/evolution', {
        params: { path, query: { month: query.month, months: query.months } },
      })
    case 'method':
      return client.GET('/api/v1/workspaces/{workspaceId}/health/method', { params: { path } })
    case 'status':
      return client.GET('/api/v1/workspaces/{workspaceId}/health/status', { params: { path } })
  }
}
