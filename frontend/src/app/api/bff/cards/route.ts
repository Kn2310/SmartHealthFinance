import type { NextRequest } from 'next/server'
import { backendClient } from '@/server/backend'
import { rejectCrossOrigin, withSession } from '@/server/bff'
import { cardFailure, readCardBody } from '@/server/cards'
import { noStoreJson } from '@/server/http'

export const dynamic = 'force-dynamic'

/**
 * Cartões do Workspace da sessão (ADR-0026 §2). Sem parâmetro, só os ativos (destino de compras e pagamentos);
 * `?includeArchived=true` inclui os arquivados (nomes no histórico). Só os 4 últimos dígitos trafegam.
 */
export async function GET(request: NextRequest) {
  const includeArchived = request.nextUrl.searchParams.get('includeArchived') === 'true'
  return withSession('cards', async (session) => {
    const { data, error, response } = await backendClient(session.accessToken).GET(
      '/api/v1/workspaces/{workspaceId}/cards',
      { params: { path: { workspaceId: session.workspaceId }, query: { includeArchived } } },
    )
    if (data) return noStoreJson(data)
    return cardFailure('cards', response.status, error)
  })
}

/** Cadastro mínimo do cartão (201). O corpo é validado e remontado aqui: número completo e CVV nunca passam. */
export async function POST(request: NextRequest) {
  const forbidden = rejectCrossOrigin(request)
  if (forbidden) return forbidden

  return withSession('card create', async (session) => {
    const parsed = await readCardBody(request)
    if ('response' in parsed) return parsed.response
    const { data, error, response } = await backendClient(session.accessToken).POST(
      '/api/v1/workspaces/{workspaceId}/cards',
      { params: { path: { workspaceId: session.workspaceId } }, body: parsed.body },
    )
    if (data) return noStoreJson(data, 201)
    return cardFailure('card create', response.status, error)
  })
}
