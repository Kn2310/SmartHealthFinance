import type { NextRequest } from 'next/server'
import { monthParam, readCardResource } from '@/server/cards'

export const dynamic = 'force-dynamic'

/** Faturas do cartão (ADR-0026 §4), da mais recente para a mais antiga; `from`/`to` malformados caem no padrão. */
export async function GET(request: NextRequest, { params }: { params: Promise<{ cardId: string }> }) {
  const { cardId } = await params
  const search = request.nextUrl.searchParams
  const query = { from: monthParam(search, 'from'), to: monthParam(search, 'to') }
  return readCardResource('card invoices', cardId, (client, path) =>
    client.GET('/api/v1/workspaces/{workspaceId}/cards/{cardId}/invoices', { params: { path, query } }),
  )
}
