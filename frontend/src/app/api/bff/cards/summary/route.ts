import { backendClient } from '@/server/backend'
import { withSession } from '@/server/bff'
import { cardFailure } from '@/server/cards'
import { noStoreJson } from '@/server/http'

export const dynamic = 'force-dynamic'

/** Resumo dos cartões ativos (ADR-0026 §3): totais, fatura atual e utilização de cada um, numa chamada só. */
export async function GET() {
  return withSession('cards summary', async (session) => {
    const { data, error, response } = await backendClient(session.accessToken).GET(
      '/api/v1/workspaces/{workspaceId}/cards/summary',
      { params: { path: { workspaceId: session.workspaceId } } },
    )
    if (data) return noStoreJson(data)
    return cardFailure('cards summary', response.status, error)
  })
}
