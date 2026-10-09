import type { NextRequest } from 'next/server'
import { accountFailure } from '@/server/accounts'
import { backendClient } from '@/server/backend'
import { isUuid, rejectCrossOrigin, withSession } from '@/server/bff'
import { noStoreJson } from '@/server/http'

export const dynamic = 'force-dynamic'

/** Arquiva a conta: o histórico fica, ela sai do saldo total e não recebe movimento. Idempotente no backend. */
export async function POST(request: NextRequest, { params }: { params: Promise<{ accountId: string }> }) {
  const forbidden = rejectCrossOrigin(request)
  if (forbidden) return forbidden
  const { accountId } = await params
  if (!isUuid(accountId)) return noStoreJson({ code: 'ACCOUNT_NOT_FOUND' }, 404)

  return withSession('account archive', async (session) => {
    const { data, error, response } = await backendClient(session.accessToken).POST(
      '/api/v1/workspaces/{workspaceId}/accounts/{accountId}/archive',
      { params: { path: { workspaceId: session.workspaceId, accountId } } },
    )
    if (data) return noStoreJson(data)
    return accountFailure('account archive', response.status, error)
  })
}
