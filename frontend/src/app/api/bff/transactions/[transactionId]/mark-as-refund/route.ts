import type { NextRequest } from 'next/server'
import { backendClient } from '@/server/backend'
import { isUuid, rejectCrossOrigin, withSession } from '@/server/bff'
import { noStoreJson } from '@/server/http'
import { readMarkAsRefundBody, transactionFailure } from '@/server/transactions'

export const dynamic = 'force-dynamic'

type Context = { params: Promise<{ transactionId: string }> }

/**
 * "Marcar como reembolso": a receita importada é estornada e substituída por um REFUND igual, com vínculo opcional à
 * despesa original. Idempotente no backend (201 na primeira vez, 200 no replay); responde o reembolso criado.
 */
export async function POST(request: NextRequest, { params }: Context) {
  const forbidden = rejectCrossOrigin(request)
  if (forbidden) return forbidden
  const { transactionId } = await params
  if (!isUuid(transactionId)) return noStoreJson({ code: 'TRANSACTION_NOT_FOUND' }, 404)

  return withSession('transaction mark-as-refund', async (session) => {
    const parsed = await readMarkAsRefundBody(request)
    if ('response' in parsed) return parsed.response

    const { data, error, response } = await backendClient(session.accessToken).POST(
      '/api/v1/workspaces/{workspaceId}/transactions/{transactionId}/mark-as-refund',
      { params: { path: { workspaceId: session.workspaceId, transactionId } }, body: parsed.body },
    )
    if (data) return noStoreJson(data, response.status === 201 ? 201 : 200)
    return transactionFailure('transaction mark-as-refund', response.status, error)
  })
}
