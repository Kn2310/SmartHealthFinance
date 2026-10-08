import type { NextRequest } from 'next/server'
import { backendClient } from '@/server/backend'
import { isUuid, rejectCrossOrigin, withSession } from '@/server/bff'
import { noStoreJson } from '@/server/http'
import { importFailure } from '@/server/imports'

export const dynamic = 'force-dynamic'

/** Descarta o preview (as linhas são apagadas no backend). Idempotente. */
export async function POST(request: NextRequest, { params }: { params: Promise<{ importId: string }> }) {
  const forbidden = rejectCrossOrigin(request)
  if (forbidden) return forbidden
  const { importId } = await params
  if (!isUuid(importId)) return noStoreJson({ code: 'IMPORT_NOT_FOUND' }, 404)

  return withSession('import cancel', async (session) => {
    const { data, error, response } = await backendClient(session.accessToken).POST(
      '/api/v1/workspaces/{workspaceId}/imports/{importId}/cancel',
      { params: { path: { workspaceId: session.workspaceId, importId } } },
    )
    if (data) return noStoreJson(data)
    return importFailure('import cancel', response.status, error)
  })
}
