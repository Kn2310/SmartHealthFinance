import type { NextRequest } from 'next/server'
import { intParam, readCardResource } from '@/server/cards'

export const dynamic = 'force-dynamic'

/** Evolução da utilização (ADR-0029 §2): fim de cada mês anterior e hoje; `months` de 1 a 12 (padrão 6). */
export async function GET(request: NextRequest, { params }: { params: Promise<{ cardId: string }> }) {
  const { cardId } = await params
  const months = intParam(request.nextUrl.searchParams, 'months', 1, 12)
  return readCardResource('card utilization history', cardId, (client, path) =>
    client.GET('/api/v1/workspaces/{workspaceId}/cards/{cardId}/utilization/history', {
      params: { path, query: { months } },
    }),
  )
}
