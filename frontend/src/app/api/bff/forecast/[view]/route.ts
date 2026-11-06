import type { NextRequest } from 'next/server'
import { backendClient } from '@/server/backend'
import { withSession } from '@/server/bff'
import { forecastFailure, isForecastView, type ForecastView } from '@/server/forecast'
import { noStoreJson } from '@/server/http'
import { isUuid } from '@/lib/uuid'

export const dynamic = 'force-dynamic'

type Context = { params: Promise<{ view: string }> }

/**
 * Leituras do Forecast (ADR-0032 §1): repassa UMA chamada ao snapshot do Workspace da sessão. O corpo de sucesso é o
 * contrato do backend, sem recálculo. `invoices` aceita `?cardId=` (UUID); qualquer outro valor é 404 sem chamar a API.
 */
export async function GET(request: NextRequest, { params }: Context) {
  const { view } = await params
  if (!isForecastView(view)) return noStoreJson({ code: 'NOT_FOUND' }, 404)
  const cardId = request.nextUrl.searchParams.get('cardId')
  if (view === 'invoices' && cardId !== null && !isUuid(cardId)) return noStoreJson({ code: 'CARD_NOT_FOUND' }, 404)

  return withSession(`forecast ${view}`, async (session) => {
    const { data, error, response } = await call(session.accessToken, session.workspaceId, view, cardId ?? undefined)
    if (data) return noStoreJson(data)
    return forecastFailure(`forecast ${view}`, response.status, error)
  })
}

function call(token: string, workspaceId: string, view: ForecastView, cardId: string | undefined) {
  const client = backendClient(token)
  const path = { workspaceId }
  switch (view) {
    case 'month':
      return client.GET('/api/v1/workspaces/{workspaceId}/forecast/month', { params: { path } })
    case 'next-income':
      return client.GET('/api/v1/workspaces/{workspaceId}/forecast/next-income', { params: { path } })
    case 'upcoming':
      return client.GET('/api/v1/workspaces/{workspaceId}/forecast/upcoming', { params: { path } })
    case 'invoices':
      return client.GET('/api/v1/workspaces/{workspaceId}/forecast/invoices', { params: { path, query: { cardId } } })
    case 'cash-flow':
      return client.GET('/api/v1/workspaces/{workspaceId}/forecast/cash-flow', { params: { path } })
    case 'status':
      return client.GET('/api/v1/workspaces/{workspaceId}/forecast/status', { params: { path } })
  }
}
