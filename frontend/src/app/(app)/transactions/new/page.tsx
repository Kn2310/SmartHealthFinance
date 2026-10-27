import type { Metadata } from 'next'
import { NewTransactionView, type FormType } from '@/features/transactions/NewTransactionView'
import { parseReturnTo } from '@/features/transactions/return-to'
import { isUuid } from '@/lib/uuid'

export const metadata: Metadata = { title: 'Nova transação' }

const TYPES: readonly FormType[] = [
  'INCOME', 'EXPENSE', 'ADJUSTMENT', 'TRANSFER', 'REFUND', 'CARD_PURCHASE', 'CARD_PAYMENT', 'CARD_CREDIT',
]

/**
 * `?accountId=` pré-seleciona a conta; `?returnTo=account` volta para ela; `?type=` escolhe o tipo (só os nomes da
 * lista); `?refundOf=` vincula o reembolso ou o estorno no cartão à despesa ou compra (só UUID); `?cardId=` escolhe o
 * cartão da compra, do pagamento ou do estorno (só UUID). Nunca uma URL nem dado financeiro na query.
 */
export default async function NewTransactionPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>
}) {
  const { accountId, returnTo, type, refundOf, cardId } = await searchParams
  const initialType = TYPES.find((value) => value === type) ?? null
  return (
    <NewTransactionView
      initialAccountId={isUuid(accountId) ? accountId : null}
      returnTo={parseReturnTo(returnTo, accountId)}
      initialType={initialType}
      refundOf={(initialType === 'REFUND' || initialType === 'CARD_CREDIT') && isUuid(refundOf) ? refundOf : null}
      initialCardId={isUuid(cardId) ? cardId : null}
    />
  )
}
