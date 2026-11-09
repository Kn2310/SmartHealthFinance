import type { NextRequest } from 'next/server'
import { backendClient } from '@/server/backend'
import { rejectCrossOrigin, withSession } from '@/server/bff'
import { consentFailure, isConsentPurpose, readConsentChange } from '@/server/consents'
import { noStoreJson } from '@/server/http'

export const dynamic = 'force-dynamic'

type Context = { params: Promise<{ purpose: string }> }

/** Concede ou revoga (idempotente). Devolve a lista inteira: revogar o uso de dados revoga o histórico (ADR-0038 §3). */
export async function PUT(request: NextRequest, { params }: Context) {
  const forbidden = rejectCrossOrigin(request)
  if (forbidden) return forbidden
  const { purpose } = await params
  if (!isConsentPurpose(purpose)) return noStoreJson({ code: 'CONSENT_PURPOSE_NOT_FOUND' }, 404)

  return withSession('consent change', async (session) => {
    const parsed = await readConsentChange(request)
    if ('response' in parsed) return parsed.response

    const { data, error, response } = await backendClient(session.accessToken).PUT(
      '/api/v1/workspaces/{workspaceId}/consents/{purpose}',
      { params: { path: { workspaceId: session.workspaceId, purpose } }, body: parsed.fields },
    )
    if (data) return noStoreJson(data)
    return consentFailure('consent change', response.status, error)
  })
}
