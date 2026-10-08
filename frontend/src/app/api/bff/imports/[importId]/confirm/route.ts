import type { NextRequest } from 'next/server'
import { backendClient } from '@/server/backend'
import { isUuid, rejectCrossOrigin, withSession } from '@/server/bff'
import { noStoreJson } from '@/server/http'
import { importFailure } from '@/server/imports'

export const dynamic = 'force-dynamic'

/** Confirma o preview (202): as transações são criadas de forma assíncrona. Idempotente no backend. */
export async function POST(request: NextRequest, { params }: { params: Promise<{ importId: string }> }) {
  const forbidden = rejectCrossOrigin(request)
  if (forbidden) return forbidden
  const { importId } = await params
  if (!isUuid(importId)) return noStoreJson({ code: 'IMPORT_NOT_FOUND' }, 404)

  return withSession('import confirm', async (session) => {
    const { data, error, response } = await backendClient(session.accessToken).POST(
      '/api/v1/workspaces/{workspaceId}/imports/{importId}/confirm',
      { params: { path: { workspaceId: session.workspaceId, importId } } },
    )
    if (data) return noStoreJson(data, 202)
    return importFailure('import confirm', response.status, error)
  })
}
