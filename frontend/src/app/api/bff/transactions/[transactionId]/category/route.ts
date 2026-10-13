import type { NextRequest } from 'next/server'
import { backendClient } from '@/server/backend'
import { isUuid, rejectCrossOrigin, withSession } from '@/server/bff'
import { noStoreJson } from '@/server/http'
import { readCategoryBody, transactionFailure } from '@/server/transactions'

export const dynamic = 'force-dynamic'

type Context = { params: Promise<{ transactionId: string }> }

/**
 * Atribui ou remove (`null`) a categoria (ADR-0012 §7). Idempotente no backend; vale em qualquer status.
 * Código fora do catálogo ou de natureza incompatível com o tipo → 400 com o código estável do campo.
 */
export async function PUT(request: NextRequest, { params }: Context) {
  const forbidden = rejectCrossOrigin(request)
  if (forbidden) return forbidden
  const { transactionId } = await params
  if (!isUuid(transactionId)) return noStoreJson({ code: 'TRANSACTION_NOT_FOUND' }, 404)

  return withSession('transaction categorize', async (session) => {
    const parsed = await readCategoryBody(request)
    if ('response' in parsed) return parsed.response

    const { data, error, response } = await backendClient(session.accessToken).PUT(
      '/api/v1/workspaces/{workspaceId}/transactions/{transactionId}/category',
      { params: { path: { workspaceId: session.workspaceId, transactionId } }, body: parsed.body },
    )
    if (data) return noStoreJson(data)
    return transactionFailure('transaction categorize', response.status, error)
  })
}
