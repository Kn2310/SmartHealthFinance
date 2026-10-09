import type { NextRequest } from 'next/server'
import { isUuid, rejectCrossOrigin, withSession } from '@/server/bff'
import { backendClient } from '@/server/backend'
import { noStoreJson } from '@/server/http'
import { readJsonObject, readOpeningBalance, recordOpeningBalance, transactionFailure } from '@/server/transactions'

export const dynamic = 'force-dynamic'

/**
 * "Tentar lançar saldo inicial novamente" depois de uma criação de conta em que o ajuste falhou. Usa a mesma
 * chave determinística da criação (`opening-balance:<accountId>`): se a primeira tentativa tiver sido gravada,
 * nada é duplicado — mesmo conteúdo devolve o original; conteúdo diferente vira `OPENING_BALANCE_EXISTS` (409).
 */
export async function POST(request: NextRequest, { params }: { params: Promise<{ accountId: string }> }) {
  const forbidden = rejectCrossOrigin(request)
  if (forbidden) return forbidden
  const { accountId } = await params
  if (!isUuid(accountId)) return noStoreJson({ code: 'ACCOUNT_NOT_FOUND' }, 404)

  return withSession('account opening balance', async (session) => {
    const body = await readJsonObject(request)
    if (!body) return noStoreJson({ code: 'INVALID_REQUEST' }, 400)
    const opening = readOpeningBalance(body)
    if ('response' in opening) return opening.response
    if (!opening.value) return noStoreJson({ code: 'INVALID_REQUEST' }, 400)

    const result = await recordOpeningBalance(
      backendClient(session.accessToken),
      session.workspaceId,
      accountId,
      opening.value,
    )
    if (result.outcome === 'RECORDED') return noStoreJson({ openingBalance: 'RECORDED' })
    if (result.outcome === 'ALREADY_RECORDED') return noStoreJson({ code: 'OPENING_BALANCE_EXISTS' }, 409)
    return transactionFailure('account opening balance', result.status, result.error)
  })
}
