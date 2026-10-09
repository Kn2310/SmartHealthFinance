import type { NextRequest } from 'next/server'
import { accountFailure, readAccountFields } from '@/server/accounts'
import { backendClient } from '@/server/backend'
import { isUuid, rejectCrossOrigin, withSession } from '@/server/bff'
import { noStoreJson } from '@/server/http'

export const dynamic = 'force-dynamic'

type Context = { params: Promise<{ accountId: string }> }

/** Uma conta do Workspace da sessão. De outro Workspace ou inexistente: o mesmo 404 (ADR-0004 §2). */
export async function GET(_request: NextRequest, { params }: Context) {
  const { accountId } = await params
  if (!isUuid(accountId)) return noStoreJson({ code: 'ACCOUNT_NOT_FOUND' }, 404)

  return withSession('account', async (session) => {
    const { data, error, response } = await backendClient(session.accessToken).GET(
      '/api/v1/workspaces/{workspaceId}/accounts/{accountId}',
      { params: { path: { workspaceId: session.workspaceId, accountId } } },
    )
    if (data) return noStoreJson(data)
    return accountFailure('account', response.status, error)
  })
}

/** Edição: substituição completa de nome, tipo, instituição e inclusão no saldo total (ADR-0004 §8). */
export async function PUT(request: NextRequest, { params }: Context) {
  const forbidden = rejectCrossOrigin(request)
  if (forbidden) return forbidden
  const { accountId } = await params
  if (!isUuid(accountId)) return noStoreJson({ code: 'ACCOUNT_NOT_FOUND' }, 404)

  return withSession('account update', async (session) => {
    const parsed = await readAccountFields(request, 'update')
    if ('response' in parsed) return parsed.response

    const { data, error, response } = await backendClient(session.accessToken).PUT(
      '/api/v1/workspaces/{workspaceId}/accounts/{accountId}',
      { params: { path: { workspaceId: session.workspaceId, accountId } }, body: parsed.fields },
    )
    if (data) return noStoreJson(data)
    return accountFailure('account update', response.status, error)
  })
}
