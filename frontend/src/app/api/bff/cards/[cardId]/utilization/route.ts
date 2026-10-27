import type { NextRequest } from 'next/server'
import { readCardResource } from '@/server/cards'

export const dynamic = 'force-dynamic'

/** Utilização do limite com a composição (ADR-0025 §9, ADR-0026 §6). */
export async function GET(_request: NextRequest, { params }: { params: Promise<{ cardId: string }> }) {
  const { cardId } = await params
  return readCardResource('card utilization', cardId, (client, path) =>
    client.GET('/api/v1/workspaces/{workspaceId}/cards/{cardId}/utilization', { params: { path } }),
  )
}
