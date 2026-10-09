import type { NextRequest } from 'next/server'
import { backendClient } from '@/server/backend'
import { rejectCrossOrigin, withSession } from '@/server/bff'
import { noStoreJson } from '@/server/http'
import { parseListQuery, readCreateBody, readIdempotencyKey, transactionFailure } from '@/server/transactions'

export const dynamic = 'force-dynamic'

/**
 * Lista paginada das transações do Workspace da sessão (ADR-0005 §1). Só filtros conhecidos e bem formados
 * passam (`from`, `to`, `type`, `status`, `accountId`, `q`, `page`); o tamanho da página é fixo no BFF.
 */
export async function GET(request: NextRequest) {
  const query = parseListQuery(request.nextUrl.searchParams)
  return withSession('transactions', async (session) => {
    const { data, error, response } = await backendClient(session.accessToken).GET(
      '/api/v1/workspaces/{workspaceId}/transactions',
      { params: { path: { workspaceId: session.workspaceId }, query } },
    )
    if (data) return noStoreJson(data)
    return transactionFailure('transactions', response.status, error)
  })
}

/**
 * Lançamento manual (receita, despesa, ajuste). A `Idempotency-Key` do browser é obrigatória e repassada sem
 * alteração: um duplo clique ou retry com a mesma chave devolve a transação original (200), nunca duplica.
 */
export async function POST(request: NextRequest) {
  const forbidden = rejectCrossOrigin(request)
  if (forbidden) return forbidden

  return withSession('transaction create', async (session) => {
    const idempotencyKey = readIdempotencyKey(request)
    if (!idempotencyKey) return noStoreJson({ code: 'INVALID_REQUEST' }, 400)
    const parsed = await readCreateBody(request)
    if ('response' in parsed) return parsed.response

    const { data, error, response } = await backendClient(session.accessToken).POST(
      '/api/v1/workspaces/{workspaceId}/transactions',
      {
        params: { path: { workspaceId: session.workspaceId }, header: { 'Idempotency-Key': idempotencyKey } },
        body: parsed.body,
      },
    )
    if (data) return noStoreJson(data, response.status === 201 ? 201 : 200)
    return transactionFailure('transaction create', response.status, error)
  })
}
