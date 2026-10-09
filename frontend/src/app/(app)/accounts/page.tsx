import type { Metadata } from 'next'
import { AccountsView } from '@/features/accounts/AccountsView'

export const metadata: Metadata = { title: 'Contas' }

export default function AccountsPage() {
  return <AccountsView />
}
