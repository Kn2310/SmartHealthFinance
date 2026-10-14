import type { NextRequest } from 'next/server'
import { backendClient } from '@/server/backend'
import { withSession } from '@/server/bff'
import { noStoreJson } from '@/server/http'
import { analysisFailure, isCategoryCode, parseAnalysisQuery } from '@/server/analytics'

export const dynamic = 'force-dynamic'

type Context = { params: Promise<{ categoryCode: string }> }

/** Detalhe de uma categoria na Análise (ADR-0015 §6). Código fora do formato do catálogo nem chega ao backend. */
export async function GET(request: NextRequest, { params }: Context) {
  const { categoryCode } = await params
  if (!isCategoryCode(categoryCode)) {
    return noStoreJson({ code: 'VALIDATION_FAILED', details: [{ field: 'categoryCode', code: 'INVALID' }] }, 400)
  }
  const query = parseAnalysisQuery(request.nextUrl.searchParams)

  return withSession('analytics category', async (session) => {
    const { data, error, response } = await backendClient(session.accessToken).GET(
      '/api/v1/workspaces/{workspaceId}/analytics/categories/{categoryCode}',
      {
        params: {
          path: { workspaceId: session.workspaceId, categoryCode },
          query: { month: query.month, compareWith: query.compareWith, averageMonths: query.averageMonths },
        },
      },
    )
    if (data) return noStoreJson(data)
    return analysisFailure('analytics category', response.status, error)
  })
}
