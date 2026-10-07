import type { ReactNode } from 'react'
import styles from './HomeView.module.css'

/** Contexto (quem e qual período) + seletor de período. Sem painel de controles. */
export function HomeHeader({ firstName, subtitle, children }: { firstName: string; subtitle: string; children?: ReactNode }) {
  return (
    <header className={styles.header}>
      <div>
        <h1 className={styles.greeting}>{firstName ? `Olá, ${firstName}` : 'Olá'}</h1>
        <p className={styles.subtitle}>{subtitle}</p>
      </div>
      {children}
    </header>
  )
}
