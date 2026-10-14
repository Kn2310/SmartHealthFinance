import type { NextRequest } from 'next/server'
import { backendClient } from '@/server/backend'
import { withSession } from '@/server/bff'
import { noStoreJson } from '@/server/http'
import { parseListQuery, transactionFailure } from '@/server/transactions'

export const dynamic = 'force-dynamic'

/**
 * Métricas do topo de D-Transactions (ADR-0016): os mesmos filtros da lista (status e paginação não se aplicam).
 */
export async function GET(request: NextRequest) {
  const { from, to, type, accountId, q, categoryCode, uncategorized } = parseListQuery(request.nextUrl.searchParams)
  return withSession('transactions summary', async (session) => {
    const { data, error, response } = await backendClient(session.accessToken).GET(
      '/api/v1/workspaces/{workspaceId}/transactions/summary',
      {
        params: {
          path: { workspaceId: session.workspaceId },
          query: { from, to, type, accountId, q, categoryCode, uncategorized },
        },
      },
    )
    if (data) return noStoreJson(data)
    return transactionFailure('transactions summary', response.status, error)
  })
}
