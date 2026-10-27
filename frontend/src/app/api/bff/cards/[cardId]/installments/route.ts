import type { NextRequest } from 'next/server'
import { readCardResource } from '@/server/cards'

export const dynamic = 'force-dynamic'

const STATUSES = ['ACTIVE', 'FINISHED', 'ALL'] as const
type Status = (typeof STATUSES)[number]

/** Parcelamentos do cartão (ADR-0026 §10); `status` fora da lista cai no padrão (ativos). */
export async function GET(request: NextRequest, { params }: { params: Promise<{ cardId: string }> }) {
  const { cardId } = await params
  const raw = request.nextUrl.searchParams.get('status')
  const status = (STATUSES as readonly string[]).includes(raw ?? '') ? (raw as Status) : undefined
  return readCardResource('card installments', cardId, (client, path) =>
    client.GET('/api/v1/workspaces/{workspaceId}/cards/{cardId}/installments', { params: { path, query: { status } } }),
  )
}
