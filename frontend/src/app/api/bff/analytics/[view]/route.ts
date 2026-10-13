import type { NextRequest } from 'next/server'
import { backendClient } from '@/server/backend'
import { withSession } from '@/server/bff'
import { noStoreJson } from '@/server/http'
import { analysisFailure, isAnalysisView, parseAnalysisQuery, type AnalysisView } from '@/server/analytics'

export const dynamic = 'force-dynamic'

type Context = { params: Promise<{ view: string }> }

/**
 * Abas da Análise (ADR-0015): repassa UMA chamada a `GET /api/v1/workspaces/{id}/analytics/{view}` do Workspace da
 * sessão. O corpo de sucesso é o contrato do backend, sem recálculo: o frontend só formata.
 */
export async function GET(request: NextRequest, { params }: Context) {
  const { view } = await params
  if (!isAnalysisView(view)) return noStoreJson({ code: 'NOT_FOUND' }, 404)
  const query = parseAnalysisQuery(request.nextUrl.searchParams)

  return withSession(`analytics ${view}`, async (session) => {
    const { data, error, response } = await call(session.accessToken, session.workspaceId, view, query)
    if (data) return noStoreJson(data)
    return analysisFailure(`analytics ${view}`, response.status, error)
  })
}

function call(token: string, workspaceId: string, view: AnalysisView, query: ReturnType<typeof parseAnalysisQuery>) {
  const client = backendClient(token)
  const path = { workspaceId }
  const base = { month: query.month, compareWith: query.compareWith, averageMonths: query.averageMonths }
  switch (view) {
    case 'overview':
      return client.GET('/api/v1/workspaces/{workspaceId}/analytics/overview', { params: { path, query: base } })
    case 'spending':
      return client.GET('/api/v1/workspaces/{workspaceId}/analytics/spending', { params: { path, query: base } })
    case 'income':
      return client.GET('/api/v1/workspaces/{workspaceId}/analytics/income', { params: { path, query: base } })
    case 'categories':
      return client.GET('/api/v1/workspaces/{workspaceId}/analytics/categories', { params: { path, query: base } })
    case 'merchants':
      return client.GET('/api/v1/workspaces/{workspaceId}/analytics/merchants', {
        params: { path, query: { ...base, sort: query.sort } },
      })
    case 'evolution':
      return client.GET('/api/v1/workspaces/{workspaceId}/analytics/evolution', {
        params: { path, query: { month: query.month, months: query.months } },
      })
    case 'status':
      return client.GET('/api/v1/workspaces/{workspaceId}/analytics/status', { params: { path } })
  }
}
