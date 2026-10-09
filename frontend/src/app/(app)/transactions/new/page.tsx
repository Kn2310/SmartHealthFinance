import type { Metadata } from 'next'
import { NewTransactionView } from '@/features/transactions/NewTransactionView'
import { parseReturnTo } from '@/features/transactions/return-to'
import { isUuid } from '@/lib/uuid'

export const metadata: Metadata = { title: 'Nova transação' }

/** `?accountId=` pré-seleciona a conta; `?returnTo=account` volta para ela. Só nomes fixos, nunca uma URL. */
export default async function NewTransactionPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>
}) {
  const { accountId, returnTo } = await searchParams
  return (
    <NewTransactionView
      initialAccountId={isUuid(accountId) ? accountId : null}
      returnTo={parseReturnTo(returnTo, accountId)}
    />
  )
}
