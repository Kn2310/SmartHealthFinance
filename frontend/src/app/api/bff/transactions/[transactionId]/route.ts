import type { NextRequest } from 'next/server'
import { backendClient } from '@/server/backend'
import { isUuid, rejectCrossOrigin, withSession } from '@/server/bff'
import { noStoreJson } from '@/server/http'
import { readDescriptionBody, transactionFailure } from '@/server/transactions'

export const dynamic = 'force-dynamic'

type Context = { params: Promise<{ transactionId: string }> }

/** Uma transação do Workspace da sessão. De outro Workspace ou inexistente: o mesmo 404 (ADR-0005 §10). */
export async function GET(_request: NextRequest, { params }: Context) {
  const { transactionId } = await params
  if (!isUuid(transactionId)) return noStoreJson({ code: 'TRANSACTION_NOT_FOUND' }, 404)

  return withSession('transaction', async (session) => {
    const { data, error, response } = await backendClient(session.accessToken).GET(
      '/api/v1/workspaces/{workspaceId}/transactions/{transactionId}',
      { params: { path: { workspaceId: session.workspaceId, transactionId } } },
    )
    if (data) return noStoreJson(data)
    return transactionFailure('transaction', response.status, error)
  })
}

/** Edita só a descrição; valor, tipo, contas e data são imutáveis (ADR-0005 §8). Edição concorrente → 409. */
export async function PUT(request: NextRequest, { params }: Context) {
  const forbidden = rejectCrossOrigin(request)
  if (forbidden) return forbidden
  const { transactionId } = await params
  if (!isUuid(transactionId)) return noStoreJson({ code: 'TRANSACTION_NOT_FOUND' }, 404)

  return withSession('transaction update', async (session) => {
    const parsed = await readDescriptionBody(request)
    if ('response' in parsed) return parsed.response

    const { data, error, response } = await backendClient(session.accessToken).PUT(
      '/api/v1/workspaces/{workspaceId}/transactions/{transactionId}',
      { params: { path: { workspaceId: session.workspaceId, transactionId } }, body: parsed.body },
    )
    if (data) return noStoreJson(data)
    return transactionFailure('transaction update', response.status, error)
  })
}
