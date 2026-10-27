import type { NextRequest } from 'next/server'
import { backendClient } from '@/server/backend'
import { isUuid, rejectCrossOrigin, withSession } from '@/server/bff'
import { cardFailure, readCardBody, readCardResource } from '@/server/cards'
import { noStoreJson } from '@/server/http'

export const dynamic = 'force-dynamic'

type Params = { params: Promise<{ cardId: string }> }

/** O cartão (ADR-0026 §2): só os 4 últimos dígitos trafegam. */
export async function GET(_request: NextRequest, { params }: Params) {
  const { cardId } = await params
  return readCardResource('card', cardId, (client, path) =>
    client.GET('/api/v1/workspaces/{workspaceId}/cards/{cardId}', { params: { path } }),
  )
}

/**
 * Edição do cartão (ADR-0026 §2; ADR-0029 §7): substitui os campos editáveis. O corpo é remontado como no cadastro,
 * então número completo e CVV nunca passam.
 */
export async function PUT(request: NextRequest, { params }: Params) {
  const forbidden = rejectCrossOrigin(request)
  if (forbidden) return forbidden
  const { cardId } = await params
  if (!isUuid(cardId)) return noStoreJson({ code: 'CARD_NOT_FOUND' }, 404)

  return withSession('card update', async (session) => {
    const parsed = await readCardBody(request)
    if ('response' in parsed) return parsed.response
    const { data, error, response } = await backendClient(session.accessToken).PUT(
      '/api/v1/workspaces/{workspaceId}/cards/{cardId}',
      { params: { path: { workspaceId: session.workspaceId, cardId } }, body: parsed.body },
    )
    if (data) return noStoreJson(data)
    return cardFailure('card update', response.status, error)
  })
}
