import type { NextRequest } from 'next/server'
import { accountFailure, readAccountFields } from '@/server/accounts'
import { backendClient } from '@/server/backend'
import { rejectCrossOrigin, withSession } from '@/server/bff'
import { noStoreJson } from '@/server/http'

export const dynamic = 'force-dynamic'

/**
 * BFF das contas do Workspace da sessão. Sem parâmetro, só as ativas (destino da importação: arquivadas não
 * recebem movimento); `?includeArchived=true` inclui as arquivadas (tela de contas). Corpo de sucesso = contrato
 * do backend, em ordem de criação.
 */
export async function GET(request: NextRequest) {
  const includeArchived = request.nextUrl.searchParams.get('includeArchived') === 'true'
  return withSession('accounts', async (session) => {
    const { data, error, response } = await backendClient(session.accessToken).GET(
      '/api/v1/workspaces/{workspaceId}/accounts',
      { params: { path: { workspaceId: session.workspaceId }, query: { includeArchived } } },
    )
    if (data) return noStoreJson(data)
    return accountFailure('accounts', response.status, error)
  })
}

/** Cria uma conta manual (201). O corpo é validado e remontado aqui antes de chegar à API (ADR-0004). */
export async function POST(request: NextRequest) {
  const forbidden = rejectCrossOrigin(request)
  if (forbidden) return forbidden

  return withSession('account create', async (session) => {
    const parsed = await readAccountFields(request, 'create')
    if ('response' in parsed) return parsed.response

    const { data, error, response } = await backendClient(session.accessToken).POST(
      '/api/v1/workspaces/{workspaceId}/accounts',
      { params: { path: { workspaceId: session.workspaceId } }, body: parsed.fields },
    )
    if (data) return noStoreJson(data, 201)
    return accountFailure('account create', response.status, error)
  })
}
