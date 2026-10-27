import { ChevronLeft, CircleAlert, SearchX } from 'lucide-react'
import Link from 'next/link'
import type { ReactNode } from 'react'
import { Button } from '@/components/ui/Button'
import { Skeleton } from '@/components/ui/Skeleton'
import { cardReadFailureMessage } from './messages'
import styles from './Cards.module.css'

/** Link de volta no topo das telas de Cartões. */
export function BackLink({ href, children }: { href: string; children: ReactNode }) {
  return (
    <Link href={href} className={styles.back}>
      <ChevronLeft size={18} strokeWidth={1.75} aria-hidden="true" />
      {children}
    </Link>
  )
}

/** Carregando (05-states): esqueleto estático, anunciado uma vez. */
export function LoadingPanel({ label, rows = 3 }: { label: string; rows?: number }) {
  return (
    <div className={styles.panel} role="status" aria-busy="true">
      <span className="sr-only">{label}</span>
      <Skeleton width="md" height="title" />
      {Array.from({ length: rows }, (_, i) => (
        <Skeleton key={i} height="row" />
      ))}
    </div>
  )
}

/** Erro sem jargão (05-states): o que aconteceu, que nada mudou e como tentar de novo. */
export function ErrorPanel({ title, code, onRetry }: { title: string; code: string; onRetry?: () => void }) {
  return (
    <section className={`${styles.state} ${styles.danger}`} role="alert" aria-label={title}>
      <span className={styles.stateIcon} aria-hidden="true">
        <CircleAlert size={24} strokeWidth={1.75} />
      </span>
      <h2 className={styles.sectionTitle}>{title}</h2>
      <p className={styles.muted}>{cardReadFailureMessage(code)}</p>
      {onRetry ? <Button onClick={onRetry}>Tentar novamente</Button> : null}
    </section>
  )
}

/** Recurso inexistente ou de outro Workspace: a mesma mensagem nos dois casos. */
export function NotFoundPanel({ title, text, href, action }: { title: string; text: string; href: string; action: string }) {
  return (
    <section className={`${styles.state} ${styles.empty}`} aria-label={title}>
      <span className={styles.stateIcon} aria-hidden="true">
        <SearchX size={28} strokeWidth={1.75} />
      </span>
      <h1 className={styles.sectionTitle}>{title}</h1>
      <p className={styles.muted}>{text}</p>
      <Button href={href}>{action}</Button>
    </section>
  )
}

/** Parte da tela não carregou (estado parcial): o resto continua disponível. */
export function PartialNotice({ children, onRetry }: { children: ReactNode; onRetry?: () => void }) {
  return (
    <div className={styles.partial} role="status">
      <CircleAlert size={16} strokeWidth={1.75} aria-hidden="true" />
      <span>
        {children}{' '}
        {onRetry ? (
          <button type="button" className={styles.link} onClick={onRetry}>
            Tentar de novo
          </button>
        ) : null}
      </span>
    </div>
  )
}
