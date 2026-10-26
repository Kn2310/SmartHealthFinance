import type { NextRequest } from 'next/server'
import { backendClient } from '@/server/backend'
import { isUuid, rejectCrossOrigin, withSession } from '@/server/bff'
import { noStoreJson } from '@/server/http'
import { IDEMPOTENCY_KEY, importFailure, MAX_IMPORT_FILE_BYTES } from '@/server/imports'

export const dynamic = 'force-dynamic'

/**
 * BFF do upload de extrato ou fatura (ADR-0009 §3/§13/§19): valida o mínimo (Origin, conta ou cartão, chave, tamanho) e repassa UM
 * multipart a `POST /api/v1/workspaces/{id}/imports`. O arquivo não é guardado nem logado aqui.
 */
export async function POST(request: NextRequest) {
  const forbidden = rejectCrossOrigin(request)
  if (forbidden) return forbidden

  return withSession('imports upload', async (session) => {
    const idempotencyKey = request.headers.get('idempotency-key')
    const form = await request.formData().catch(() => null)
    const file = form?.get('file')
    const accountId = form?.get('accountId') ?? null
    const cardId = form?.get('cardId') ?? null
    // Exatamente um destino: conta (extrato) ou cartão (fatura, ADR-0009 §19).
    const destination = isUuid(accountId) && cardId === null ? { accountId }
      : isUuid(cardId) && accountId === null ? { cardId }
        : null

    if (!idempotencyKey || !IDEMPOTENCY_KEY.test(idempotencyKey) || !destination || !(file instanceof File)) {
      return noStoreJson({ code: 'INVALID_REQUEST' }, 400)
    }
    if (file.size > MAX_IMPORT_FILE_BYTES) return noStoreJson({ code: 'FILE_TOO_LARGE' }, 413)

    const upstream = new FormData()
    upstream.append('file', file, file.name)

    const { data, error, response } = await backendClient(session.accessToken).POST(
      '/api/v1/workspaces/{workspaceId}/imports',
      {
        params: {
          path: { workspaceId: session.workspaceId },
          query: destination,
          header: { 'Idempotency-Key': idempotencyKey },
        },
        body: upstream as unknown as { file: Blob },
      },
    )
    if (data) return noStoreJson(data, response.status === 201 ? 201 : 200)
    return importFailure('imports upload', response.status, error)
  })
}
