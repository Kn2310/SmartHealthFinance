'use client'

import Link from 'next/link'
import { usePathname } from 'next/navigation'
import type { ReactNode } from 'react'
import { BOTTOM_ITEMS, SIDEBAR_ITEMS, type NavItem } from './nav-items'
import styles from './AppShell.module.css'

function isActive(pathname: string, href: string): boolean {
  return pathname === href || pathname.startsWith(`${href}/`)
}

function NavLinks({ items, pathname }: { items: NavItem[]; pathname: string }) {
  return (
    <ul className={styles.list}>
      {items.map(({ href, label, icon: Icon }) => (
        <li key={href}>
          <Link href={href} className={styles.link} aria-current={isActive(pathname, href) ? 'page' : undefined}>
            <Icon size={20} strokeWidth={1.75} aria-hidden="true" />
            <span>{label}</span>
          </Link>
        </li>
      ))}
    </ul>
  )
}

function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean)
  const first = parts[0]?.[0] ?? ''
  const last = parts.length > 1 ? (parts[parts.length - 1]?.[0] ?? '') : ''
  return (first + last).toUpperCase() || '·'
}

export function AppShell({ displayName, children }: { displayName: string; children: ReactNode }) {
  const pathname = usePathname()
  return (
    <div className={styles.shell}>
      <a href="#conteudo" className={styles.skip}>
        Ir para o conteúdo
      </a>

      <aside className={styles.sidebar}>
        <Link href="/home" className={styles.brand} aria-label="Smart Health Finance — início">
          <span className={styles.mark} aria-hidden="true" />
          <span className={styles.brandText}>
            Smart Health
            <br />
            <span className={styles.brandSub}>Finance</span>
          </span>
        </Link>
        <nav aria-label="Navegação principal">
          <NavLinks items={SIDEBAR_ITEMS} pathname={pathname} />
        </nav>
        <div className={styles.account}>
          <span className={styles.avatar} aria-hidden="true">
            {initials(displayName)}
          </span>
          <span className={styles.accountName}>{displayName}</span>
          <form action="/auth/logout" method="post" className={styles.logout}>
            <button type="submit" className={styles.logoutButton}>
              Sair
            </button>
          </form>
        </div>
      </aside>

      <main id="conteudo" className={styles.main}>
        {children}
      </main>

      <nav aria-label="Navegação principal (celular)" className={styles.bottom}>
        <NavLinks items={BOTTOM_ITEMS} pathname={pathname} />
      </nav>
    </div>
  )
}
