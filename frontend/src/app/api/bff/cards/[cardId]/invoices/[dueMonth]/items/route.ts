import type { NextRequest } from 'next/server'
import { intParam, isDueMonth, readCardResource } from '@/server/cards'

export const dynamic = 'force-dynamic'

type Params = { params: Promise<{ cardId: string; dueMonth: string }> }

/** Lançamentos da fatura, paginados (ADR-0026 §4: `pageSize` até 100). */
export async function GET(request: NextRequest, { params }: Params) {
  const { cardId, dueMonth } = await params
  const search = request.nextUrl.searchParams
  const query = { page: intParam(search, 'page', 0, 9999), pageSize: intParam(search, 'pageSize', 1, 100) }
  return readCardResource(
    'card invoice items',
    cardId,
    (client, path) =>
      client.GET('/api/v1/workspaces/{workspaceId}/cards/{cardId}/invoices/{dueMonth}/items', {
        params: { path: { ...path, dueMonth }, query },
      }),
    isDueMonth(dueMonth) ? [] : [{ field: 'dueMonth', code: 'INVALID' }],
  )
}
