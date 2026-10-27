import type { NextRequest } from 'next/server'
import { isUuid } from '@/server/bff'
import { readCardResource } from '@/server/cards'
import { noStoreJson } from '@/server/http'

export const dynamic = 'force-dynamic'

type Params = { params: Promise<{ cardId: string; transactionId: string }> }

/** Um parcelamento, com cada parcela, o limite reservado e o peso na fatura aberta (ADR-0026 §10, ADR-0029 §5). */
export async function GET(_request: NextRequest, { params }: Params) {
  const { cardId, transactionId } = await params
  if (!isUuid(transactionId)) return noStoreJson({ code: 'TRANSACTION_NOT_FOUND' }, 404)
  return readCardResource('card installment', cardId, (client, path) =>
    client.GET('/api/v1/workspaces/{workspaceId}/cards/{cardId}/installments/{transactionId}', {
      params: { path: { ...path, transactionId } },
    }),
  )
}
