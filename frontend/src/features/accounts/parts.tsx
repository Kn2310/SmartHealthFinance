import type { Account } from './api'
import styles from './Accounts.module.css'

/** Monograma da instituição (design: sem logos de terceiros). Só enfeite: o nome está ao lado. */
export function Monogram({ name }: { name: string }) {
  const letters = name
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((word) => Array.from(word)[0] ?? '')
    .join('')
    .toLocaleUpperCase('pt-BR')
  return (
    <span className={styles.monogram} aria-hidden="true">
      {letters}
    </span>
  )
}

/** Status sempre com texto (nunca só cor). Valor desconhecido = neutro. */
export function StatusBadge({ status }: { status: Account['status'] }) {
  if (status === 'ACTIVE') return <span className={`${styles.badge} ${styles.success}`}>Ativa</span>
  if (status === 'ARCHIVED') return <span className={`${styles.badge} ${styles.neutral}`}>Arquivada</span>
  return <span className={`${styles.badge} ${styles.neutral}`}>Conta</span>
}
