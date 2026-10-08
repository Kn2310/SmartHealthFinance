import type { NextRequest } from 'next/server'
import { backendClient } from '@/server/backend'
import { isUuid, withSession } from '@/server/bff'
import { noStoreJson } from '@/server/http'
import { importFailure } from '@/server/imports'

export const dynamic = 'force-dynamic'

/** Status e contagens de uma importação do Workspace da sessão: é o polling do processamento. */
export async function GET(_request: NextRequest, { params }: { params: Promise<{ importId: string }> }) {
  const { importId } = await params
  if (!isUuid(importId)) return noStoreJson({ code: 'IMPORT_NOT_FOUND' }, 404)

  return withSession('import status', async (session) => {
    const { data, error, response } = await backendClient(session.accessToken).GET(
      '/api/v1/workspaces/{workspaceId}/imports/{importId}',
      { params: { path: { workspaceId: session.workspaceId, importId } } },
    )
    if (data) return noStoreJson(data)
    return importFailure('import status', response.status, error)
  })
}
