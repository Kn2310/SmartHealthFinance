import type { NextRequest } from 'next/server'
import { readCardResource } from '@/server/cards'

export const dynamic = 'force-dynamic'

/** Faturas futuras: só o valor já comprometido em parcelas, nunca estimativa (ADR-0025 §10). */
export async function GET(_request: NextRequest, { params }: { params: Promise<{ cardId: string }> }) {
  const { cardId } = await params
  return readCardResource('card future invoices', cardId, (client, path) =>
    client.GET('/api/v1/workspaces/{workspaceId}/cards/{cardId}/invoices/future', { params: { path } }),
  )
}
