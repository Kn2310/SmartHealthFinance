import type { NextRequest } from 'next/server'
import { accountFailure } from '@/server/accounts'
import { backendClient } from '@/server/backend'
import { isUuid, rejectCrossOrigin, withSession } from '@/server/bff'
import { noStoreJson } from '@/server/http'

export const dynamic = 'force-dynamic'

/** Reativa uma conta arquivada. Idempotente no backend. */
export async function POST(request: NextRequest, { params }: { params: Promise<{ accountId: string }> }) {
  const forbidden = rejectCrossOrigin(request)
  if (forbidden) return forbidden
  const { accountId } = await params
  if (!isUuid(accountId)) return noStoreJson({ code: 'ACCOUNT_NOT_FOUND' }, 404)

  return withSession('account reactivate', async (session) => {
    const { data, error, response } = await backendClient(session.accessToken).POST(
      '/api/v1/workspaces/{workspaceId}/accounts/{accountId}/reactivate',
      { params: { path: { workspaceId: session.workspaceId, accountId } } },
    )
    if (data) return noStoreJson(data)
    return accountFailure('account reactivate', response.status, error)
  })
}
