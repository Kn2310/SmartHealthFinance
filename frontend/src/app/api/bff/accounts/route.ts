import type { NextRequest } from 'next/server'
import { accountFailure, readAccountFields } from '@/server/accounts'
import { backendClient } from '@/server/backend'
import { rejectCrossOrigin, withSession } from '@/server/bff'
import { noStoreJson } from '@/server/http'
import { readOpeningBalance, recordOpeningBalance } from '@/server/transactions'

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

/**
 * Cria uma conta manual (201). O corpo é validado e remontado aqui antes de chegar à API (ADR-0004).
 *
 * `openingBalance {amount, direction}` opcional: depois de criar a conta, o BFF lança o saldo inicial como
 * `ADJUSTMENT` (ADR-0004 §4 e §11). Não é atômico: se o ajuste falhar, a conta continua criada e a resposta
 * diz `openingBalance: "FAILED"` para a UI oferecer tentar de novo (mesma chave determinística por conta).
 */
export async function POST(request: NextRequest) {
  const forbidden = rejectCrossOrigin(request)
  if (forbidden) return forbidden

  return withSession('account create', async (session) => {
    const parsed = await readAccountFields(request, 'create')
    if ('response' in parsed) return parsed.response
    // Valida o saldo inicial ANTES de criar a conta: um valor malformado não deixa conta pela metade.
    const opening = readOpeningBalance(parsed.input.openingBalance)
    if ('response' in opening) return opening.response

    const client = backendClient(session.accessToken)
    const { data, error, response } = await client.POST('/api/v1/workspaces/{workspaceId}/accounts', {
      params: { path: { workspaceId: session.workspaceId } },
      body: parsed.fields,
    })
    if (!data) return accountFailure('account create', response.status, error)
    if (!opening.value) return noStoreJson(data, 201)

    const result = await recordOpeningBalance(client, session.workspaceId, data.id, opening.value)
    if (result.outcome === 'FAILED') console.error('account opening balance failure', { status: result.status })
    return noStoreJson({ ...data, openingBalance: result.outcome === 'FAILED' ? 'FAILED' : 'RECORDED' }, 201)
  })
}
