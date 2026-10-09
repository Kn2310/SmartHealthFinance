import { notFound } from 'next/navigation'
import { ComingSoon } from '@/components/ComingSoon'

const SECTIONS: Record<string, string> = {
  cards: 'Cartões',
  analysis: 'Análise',
  goals: 'Objetivos',
  insights: 'Insights',
  copilot: 'Copilot',
  profile: 'Perfil',
}

/**
 * Destinos da navegação aprovada ainda não implementados.
 * Rotas fora desta lista seguem 404.
 */
export default async function SectionPlaceholder({ params }: { params: Promise<{ section: string }> }) {
  const { section } = await params
  const title = SECTIONS[section]
  if (!title) notFound()
  return <ComingSoon title={title} showLogout={section === 'profile'} />
}
