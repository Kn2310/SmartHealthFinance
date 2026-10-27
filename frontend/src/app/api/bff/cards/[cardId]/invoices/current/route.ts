import type { NextRequest } from 'next/server'
import { readCardResource } from '@/server/cards'

export const dynamic = 'force-dynamic'

/** A fatura cujo período contém hoje (ADR-0026 §4). */
export async function GET(_request: NextRequest, { params }: { params: Promise<{ cardId: string }> }) {
  const { cardId } = await params
  return readCardResource('card current invoice', cardId, (client, path) =>
    client.GET('/api/v1/workspaces/{workspaceId}/cards/{cardId}/invoices/current', { params: { path } }),
  )
}
