import { Building2, FileCheck, Link2, LogOut, ShieldCheck, SlidersHorizontal, User, Wallet, type LucideIcon } from 'lucide-react'
import Link from 'next/link'
import type { ReactNode } from 'react'
import { Button } from '@/components/ui/Button'
import styles from './Consents.module.css'

type Section = { label: string; icon: LucideIcon; href?: string; current?: boolean }

/** Seções do Perfil (D-ProfileConsents). Só Consentimentos e Contas existem; as demais seguem "Em breve" (ADR-0038 §9). */
const SECTIONS: Section[] = [
  { label: 'Dados pessoais', icon: User },
  { label: 'Instituições', icon: Building2 },
  { label: 'Contas', icon: Wallet, href: '/accounts' },
  { label: 'Conexões', icon: Link2 },
  { label: 'Consentimentos', icon: FileCheck, href: '/profile/consents', current: true },
  { label: 'Segurança', icon: ShieldCheck },
  { label: 'Preferências', icon: SlidersHorizontal },
]

/** Cabeçalho "Perfil" + submenu de seções; o conteúdo da seção fica à direita (empilhado no mobile). */
export function ProfileFrame({ children }: { children: ReactNode }) {
  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <h1 className={styles.pageTitle}>Perfil</h1>
        <p className={styles.subtitle}>Seus dados, conexões e preferências.</p>
      </header>
      <div className={styles.layout}>
        <nav aria-label="Seções do perfil" className={styles.nav}>
          <ul className={styles.navList}>
            {SECTIONS.map(({ label, icon: Icon, href, current }) => (
              <li key={label}>
                {href ? (
                  <Link
                    href={href}
                    className={`${styles.navItem} ${current ? styles.navCurrent : ''}`}
                    aria-current={current ? 'page' : undefined}
                  >
                    <Icon size={18} strokeWidth={1.75} aria-hidden="true" />
                    {label}
                  </Link>
                ) : (
                  <span className={`${styles.navItem} ${styles.navSoon}`} aria-disabled="true">
                    <Icon size={18} strokeWidth={1.75} aria-hidden="true" />
                    {label}
                    <span className={styles.soonBadge}>Em breve</span>
                  </span>
                )}
              </li>
            ))}
          </ul>
          <form action="/auth/logout" method="post" className={styles.logout}>
            <Button type="submit" variant="secondary">
              <LogOut size={18} strokeWidth={1.75} aria-hidden="true" />
              Sair
            </Button>
          </form>
        </nav>
        {children}
      </div>
    </div>
  )
}
