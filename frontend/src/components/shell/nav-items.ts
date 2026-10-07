import { ChartColumn, CreditCard, House, LayoutGrid, Lightbulb, Sparkles, Target, User, type LucideIcon } from 'lucide-react'

export interface NavItem {
  href: string
  label: string
  icon: LucideIcon
}

/** Sidebar (desktop) e trilho (tablet) — design/specs 04.3. */
export const SIDEBAR_ITEMS: NavItem[] = [
  { href: '/home', label: 'Início', icon: House },
  { href: '/cards', label: 'Cartões', icon: CreditCard },
  { href: '/analysis', label: 'Análise', icon: ChartColumn },
  { href: '/goals', label: 'Objetivos', icon: Target },
  { href: '/insights', label: 'Insights', icon: Lightbulb },
  { href: '/copilot', label: 'Copilot', icon: Sparkles },
  { href: '/profile', label: 'Perfil', icon: User },
]

/** Bottom nav (mobile): Início, Cartões, Análise, IA, Mais — design/specs 06. */
export const BOTTOM_ITEMS: NavItem[] = [
  { href: '/home', label: 'Início', icon: House },
  { href: '/cards', label: 'Cartões', icon: CreditCard },
  { href: '/analysis', label: 'Análise', icon: ChartColumn },
  { href: '/copilot', label: 'IA', icon: Sparkles },
  { href: '/more', label: 'Mais', icon: LayoutGrid },
]
