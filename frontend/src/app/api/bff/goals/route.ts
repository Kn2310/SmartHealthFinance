import type { NextRequest } from 'next/server'
import { backendClient } from '@/server/backend'
import { rejectCrossOrigin, withSession } from '@/server/bff'
import { goalFailure, parseGoalStatus, readCreateGoalBody } from '@/server/goals'
import { noStoreJson } from '@/server/http'

export const dynamic = 'force-dynamic'

/** Lista dos objetivos do Workspace da sessão com o resumo (ADR-0035 §1); `?status=` só com um status conhecido. */
export async function GET(request: NextRequest) {
  const status = parseGoalStatus(request.nextUrl.searchParams.get('status'))
  return withSession('goals', async (session) => {
    const { data, error, response } = await backendClient(session.accessToken).GET('/api/v1/workspaces/{workspaceId}/goals', {
      params: { path: { workspaceId: session.workspaceId }, query: status ? { status } : {} },
    })
    if (data) return noStoreJson(data)
    return goalFailure('goals', response.status, error)
  })
}

/** Cria o objetivo (ADR-0035 §1). O corpo é remontado só com os campos do contrato. */
export async function POST(request: NextRequest) {
  const forbidden = rejectCrossOrigin(request)
  if (forbidden) return forbidden
  return withSession('goal create', async (session) => {
    const parsed = await readCreateGoalBody(request)
    if ('response' in parsed) return parsed.response
    const { data, error, response } = await backendClient(session.accessToken).POST('/api/v1/workspaces/{workspaceId}/goals', {
      params: { path: { workspaceId: session.workspaceId } },
      body: parsed.body,
    })
    if (data) return noStoreJson(data, 201)
    return goalFailure('goal create', response.status, error)
  })
}
