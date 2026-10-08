import type { NextRequest } from 'next/server'
import { RECORD_STATUSES } from '@/lib/api/contract'
import { backendClient } from '@/server/backend'
import { isUuid, withSession } from '@/server/bff'
import { noStoreJson } from '@/server/http'
import { importFailure } from '@/server/imports'

export const dynamic = 'force-dynamic'

const PAGE_SIZE = 50

type RecordStatus = (typeof RECORD_STATUSES)[number]
const isRecordStatus = (value: string | null): value is RecordStatus =>
  value !== null && (RECORD_STATUSES as readonly string[]).includes(value)

/** Linhas do preview/resultado. Só `status` (enum conhecido) e `page` (inteiro ≥ 0) passam adiante. */
export async function GET(request: NextRequest, { params }: { params: Promise<{ importId: string }> }) {
  const { importId } = await params
  if (!isUuid(importId)) return noStoreJson({ code: 'IMPORT_NOT_FOUND' }, 404)

  const search = request.nextUrl.searchParams
  const statusParam = search.get('status')
  const status = isRecordStatus(statusParam) ? statusParam : undefined
  const pageParam = Number(search.get('page') ?? '0')
  const page = Number.isInteger(pageParam) && pageParam >= 0 && pageParam < 1000 ? pageParam : 0

  return withSession('import records', async (session) => {
    const { data, error, response } = await backendClient(session.accessToken).GET(
      '/api/v1/workspaces/{workspaceId}/imports/{importId}/records',
      {
        params: {
          path: { workspaceId: session.workspaceId, importId },
          query: { status, page, pageSize: PAGE_SIZE },
        },
      },
    )
    if (data) return noStoreJson(data)
    return importFailure('import records', response.status, error)
  })
}
