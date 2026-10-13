import { backendClient } from '@/server/backend'
import { withSession } from '@/server/bff'
import { noStoreJson } from '@/server/http'
import { transactionFailure } from '@/server/transactions'

export const dynamic = 'force-dynamic'

/** Catálogo de categorias (ADR-0012 §6), na ordem de exibição e com as inativas, para nomes, filtro e edição. */
export async function GET() {
  return withSession('categories', async (session) => {
    const { data, error, response } = await backendClient(session.accessToken).GET(
      '/api/v1/workspaces/{workspaceId}/categories',
      { params: { path: { workspaceId: session.workspaceId } } },
    )
    if (data) return noStoreJson(data)
    return transactionFailure('categories', response.status, error)
  })
}
