import type { Metadata } from 'next'
import { AccountDetailView } from '@/features/accounts/AccountDetailView'
import { isUuid } from '@/lib/uuid'

export const metadata: Metadata = { title: 'Conta' }

/** `?registered=` só traz o nome de um aviso fixo (depois de registrar uma transação), nunca dados. */
export default async function AccountPage({
  params,
  searchParams,
}: {
  params: Promise<{ accountId: string }>
  searchParams: Promise<Record<string, string | string[] | undefined>>
}) {
  const { accountId } = await params
  const { registered } = await searchParams
  return (
    <AccountDetailView
      id={isUuid(accountId) ? accountId : null}
      registered={typeof registered === 'string' ? registered : null}
    />
  )
}
