import type { Metadata } from 'next'
import { NewCardView } from '@/features/cards/NewCardView'

export const metadata: Metadata = { title: 'Adicionar cartão' }

/** Cadastro de cartão (ADR-0026 §10). `?from=cards` só decide para onde voltar, nunca traz dados. */
export default async function NewCardPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const { from } = await searchParams
  return <NewCardView from={from === 'cards' ? 'cards' : null} />
}
