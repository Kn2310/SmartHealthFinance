import type { NextRequest } from 'next/server'
import { backendClient } from '@/server/backend'
import { isUuid, rejectCrossOrigin, withSession } from '@/server/bff'
import { goalFailure, goalNotFound, intParam, readContributionBody } from '@/server/goals'
import { noStoreJson } from '@/server/http'
import { readIdempotencyKey } from '@/server/transactions'

export const dynamic = 'force-dynamic'

type Context = { params: Promise<{ goalId: string }> }

/** Histórico do destino (ADR-0035 §3): saldo de abertura e movimentos, paginados. */
export async function GET(request: NextRequest, { params }: Context) {
  const { goalId } = await params
  if (!isUuid(goalId)) return goalNotFound()
  const search = request.nextUrl.searchParams
  const page = intParam(search.get('page'), 0, 10_000)
  const pageSize = intParam(search.get('pageSize'), 1, 50)
  return withSession('goal contributions', async (session) => {
    const { data, error, response } = await backendClient(session.accessToken).GET(
      '/api/v1/workspaces/{workspaceId}/goals/{goalId}/contributions',
      {
        params: {
          path: { workspaceId: session.workspaceId, goalId },
          query: { ...(page !== undefined ? { page } : {}), ...(pageSize !== undefined ? { pageSize } : {}) },
        },
      },
    )
    if (data) return noStoreJson(data)
    return goalFailure('goal contributions', response.status, error)
  })
}

/**
 * "Registrar aporte" (ADR-0035 §2): cria uma TRANSFER pelo backend. A `Idempotency-Key` do browser é obrigatória e
 * repassada como veio: duplo clique ou nova tentativa devolvem a mesma transação (200), nunca duplicam.
 */
export async function POST(request: NextRequest, { params }: Context) {
  const forbidden = rejectCrossOrigin(request)
  if (forbidden) return forbidden
  const { goalId } = await params
  if (!isUuid(goalId)) return goalNotFound()
  return withSession('goal contribution', async (session) => {
    const idempotencyKey = readIdempotencyKey(request)
    if (!idempotencyKey) return noStoreJson({ code: 'INVALID_REQUEST' }, 400)
    const parsed = await readContributionBody(request)
    if ('response' in parsed) return parsed.response
    const { data, error, response } = await backendClient(session.accessToken).POST(
      '/api/v1/workspaces/{workspaceId}/goals/{goalId}/contributions',
      {
        params: { path: { workspaceId: session.workspaceId, goalId }, header: { 'Idempotency-Key': idempotencyKey } },
        body: parsed.body,
      },
    )
    if (data) return noStoreJson(data, response.status === 201 ? 201 : 200)
    return goalFailure('goal contribution', response.status, error)
  })
}
