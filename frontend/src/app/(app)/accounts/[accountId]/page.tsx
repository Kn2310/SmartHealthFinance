import type { Metadata } from 'next'
import { AccountDetailView } from '@/features/accounts/AccountDetailView'
import { isUuid } from '@/lib/uuid'

export const metadata: Metadata = { title: 'Conta' }

export default async function AccountPage({ params }: { params: Promise<{ accountId: string }> }) {
  const { accountId } = await params
  return <AccountDetailView id={isUuid(accountId) ? accountId : null} />
}
