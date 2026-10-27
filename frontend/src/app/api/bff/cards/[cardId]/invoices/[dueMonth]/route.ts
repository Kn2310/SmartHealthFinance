import type { NextRequest } from 'next/server'
import { isDueMonth, readCardResource } from '@/server/cards'

export const dynamic = 'force-dynamic'

type Params = { params: Promise<{ cardId: string; dueMonth: string }> }

/** A fatura de um mês de vencimento (ADR-0026 §4): passada sem lançamentos → 404 `INVOICE_NOT_FOUND`. */
export async function GET(_request: NextRequest, { params }: Params) {
  const { cardId, dueMonth } = await params
  const valid = isDueMonth(dueMonth)
  return readCardResource(
    'card invoice',
    cardId,
    (client, path) =>
      client.GET('/api/v1/workspaces/{workspaceId}/cards/{cardId}/invoices/{dueMonth}', {
        params: { path: { ...path, dueMonth } },
      }),
    valid ? [] : [{ field: 'dueMonth', code: 'INVALID' }],
  )
}
