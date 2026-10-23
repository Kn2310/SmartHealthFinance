import type { Metadata } from 'next'
import { NewTransactionView, type FormType } from '@/features/transactions/NewTransactionView'
import { parseReturnTo } from '@/features/transactions/return-to'
import { isUuid } from '@/lib/uuid'

export const metadata: Metadata = { title: 'Nova transação' }

const TYPES: readonly FormType[] = ['INCOME', 'EXPENSE', 'ADJUSTMENT', 'TRANSFER', 'REFUND', 'CARD_PURCHASE', 'CARD_PAYMENT']

/**
 * `?accountId=` pré-seleciona a conta; `?returnTo=account` volta para ela; `?type=` escolhe o tipo (só os nomes da
 * lista); `?refundOf=` vincula o reembolso a uma despesa (só UUID). Nunca uma URL nem dado financeiro na query.
 */
export default async function NewTransactionPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>
}) {
  const { accountId, returnTo, type, refundOf } = await searchParams
  const initialType = TYPES.find((value) => value === type) ?? null
  return (
    <NewTransactionView
      initialAccountId={isUuid(accountId) ? accountId : null}
      returnTo={parseReturnTo(returnTo, accountId)}
      initialType={initialType}
      refundOf={initialType === 'REFUND' && isUuid(refundOf) ? refundOf : null}
    />
  )
}
