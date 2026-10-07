import type { NextRequest } from 'next/server'
import { backendClient } from '@/server/backend'
import { getFreshSession } from '@/server/auth/session'
import { noStoreJson } from '@/server/http'
import { parseOverviewQuery } from '@/lib/overview-query'

export const dynamic = 'force-dynamic'

/**
 * BFF do Overview: resolve a sessão (cookie HttpOnly), o Workspace e o token, e repassa UMA chamada a
 * GET /api/v1/workspaces/{id}/overview. O corpo de sucesso é o contrato do backend, sem recálculo.
 * Falhas viram códigos genéricos: mensagens, IDs e detalhes internos do backend não chegam ao browser.
 * Toda resposta (sucesso ou erro) é `Cache-Control: no-store`.
 */
export async function GET(request: NextRequest) {
  try {
    const fresh = await getFreshSession()
    if (fresh.status === 'none') return noStoreJson({ code: 'UNAUTHENTICATED' }, 401)
    if (fresh.status === 'unavailable') return noStoreJson({ code: 'UNAVAILABLE' }, 502)
    const { session } = fresh

    // O Workspace vem SEMPRE da sessão selada; a query só aceita o período.
    const query = parseOverviewQuery(request.nextUrl.searchParams)
    const { data, response } = await backendClient(session.accessToken).GET(
      '/api/v1/workspaces/{workspaceId}/overview',
      {
        params: {
          path: { workspaceId: session.workspaceId },
          query: { period: query.period, from: query.from, to: query.to },
        },
      },
    )
    if (data) return noStoreJson(data)
    if (response.status === 400) return noStoreJson({ code: 'INVALID_PERIOD' }, 400)
    if (response.status === 401) return noStoreJson({ code: 'UNAUTHENTICATED' }, 401)
    console.error('overview upstream failure', { status: response.status })
    return noStoreJson({ code: 'UNAVAILABLE' }, 502)
  } catch {
    console.error('overview upstream unreachable')
    return noStoreJson({ code: 'UNAVAILABLE' }, 502)
  }
}
