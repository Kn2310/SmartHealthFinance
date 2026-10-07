import type { ReactNode } from 'react'
import styles from './Card.module.css'

interface CardProps {
  /** Título visível: vira o nome acessível da região. */
  title: string
  subtitle?: string
  action?: ReactNode
  id: string
  className?: string
  children: ReactNode
}

export function Card({ title, subtitle, action, id, className, children }: CardProps) {
  return (
    <section aria-labelledby={`${id}-title`} className={[styles.card, className].filter(Boolean).join(' ')}>
      <header className={styles.header}>
        <div>
          <h2 id={`${id}-title`} className={styles.title}>
            {title}
          </h2>
          {subtitle ? <p className={styles.subtitle}>{subtitle}</p> : null}
        </div>
        {action}
      </header>
      {children}
    </section>
  )
}
