import type { Metadata } from 'next'
import { NewCardView } from '@/features/cards/NewCardView'

export const metadata: Metadata = { title: 'Adicionar cartão' }

/** Cadastro mínimo de cartão (ADR-0026 §10); as telas de Cartões chegam na Sessão 6.2. */
export default function NewCardPage() {
  return <NewCardView />
}
