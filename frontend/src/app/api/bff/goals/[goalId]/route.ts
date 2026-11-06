import type { NextRequest } from 'next/server'
import { backendClient } from '@/server/backend'
import { isUuid, rejectCrossOrigin, withSession } from '@/server/bff'
import { goalFailure, goalNotFound, readUpdateGoalBody } from '@/server/goals'
import { noStoreJson } from '@/server/http'

export const dynamic = 'force-dynamic'

type Context = { params: Promise<{ goalId: string }> }

/** Detalhe com o progresso (fato) e a previsão (Estimado). Id inválido = o mesmo 404 do inexistente. */
export async function GET(_request: NextRequest, { params }: Context) {
  const { goalId } = await params
  if (!isUuid(goalId)) return goalNotFound()
  return withSession('goal', async (session) => {
    const { data, error, response } = await backendClient(session.accessToken).GET(
      '/api/v1/workspaces/{workspaceId}/goals/{goalId}',
      { params: { path: { workspaceId: session.workspaceId, goalId } } },
    )
    if (data) return noStoreJson(data)
    return goalFailure('goal', response.status, error)
  })
}

/** Edição completa (nome, plano, contas); tipo e origem não mudam (ADR-0035 §1). */
export async function PUT(request: NextRequest, { params }: Context) {
  const forbidden = rejectCrossOrigin(request)
  if (forbidden) return forbidden
  const { goalId } = await params
  if (!isUuid(goalId)) return goalNotFound()
  return withSession('goal update', async (session) => {
    const parsed = await readUpdateGoalBody(request)
    if ('response' in parsed) return parsed.response
    const { data, error, response } = await backendClient(session.accessToken).PUT(
      '/api/v1/workspaces/{workspaceId}/goals/{goalId}',
      { params: { path: { workspaceId: session.workspaceId, goalId } }, body: parsed.body },
    )
    if (data) return noStoreJson(data)
    return goalFailure('goal update', response.status, error)
  })
}
