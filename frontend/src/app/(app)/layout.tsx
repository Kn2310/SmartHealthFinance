import { redirect } from 'next/navigation'
import type { ReactNode } from 'react'
import { AppShell } from '@/components/shell/AppShell'
import { readSession } from '@/server/auth/session'

export const dynamic = 'force-dynamic'

/** Toda a área autenticada: sem sessão (cookie HttpOnly válido), segue para o login no IdP. */
export default async function AuthenticatedLayout({ children }: { children: ReactNode }) {
  const session = await readSession()
  if (!session) redirect('/auth/login')
  return <AppShell displayName={session.displayName}>{children}</AppShell>
}
