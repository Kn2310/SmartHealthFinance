import { notFound } from 'next/navigation'
import { ComingSoon } from '@/components/ComingSoon'

const SECTIONS: Record<string, string> = {
  accounts: 'Contas',
  transactions: 'Movimentações',
  cards: 'Cartões',
  analysis: 'Análise',
  goals: 'Objetivos',
  insights: 'Insights',
  copilot: 'Copilot',
  profile: 'Perfil',
  more: 'Mais',
}

/**
 * Destinos ainda não implementados que a Home já referencia (contas, movimentações) e a navegação aprovada.
 * Rotas fora desta lista seguem 404.
 */
export default async function SectionPlaceholder({ params }: { params: Promise<{ section: string }> }) {
  const { section } = await params
  const title = SECTIONS[section]
  if (!title) notFound()
  return <ComingSoon title={title} showLogout={section === 'more' || section === 'profile'} />
}
