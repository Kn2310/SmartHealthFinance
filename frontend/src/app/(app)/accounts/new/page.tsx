import type { Metadata } from 'next'
import { NewAccountView } from '@/features/accounts/NewAccountView'
import { parseReturnTo } from '@/features/accounts/return-to'

export const metadata: Metadata = { title: 'Adicionar conta' }

/** `?returnTo=` só aceita nomes de destinos internos fixos (ver `parseReturnTo`), nunca uma URL. */
export default async function NewAccountPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>
}) {
  const { returnTo } = await searchParams
  return <NewAccountView returnTo={parseReturnTo(returnTo)} />
}
