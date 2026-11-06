import Link from 'next/link'
import { ChevronRight, FileUp, Sparkles, Target, Wallet, type LucideIcon } from 'lucide-react'
import styles from './QuickActions.module.css'

const ACTIONS: { href: string | null; title: string; text: string; icon: LucideIcon }[] = [
  { href: '/import', title: 'Importar extrato', text: 'OFX ou CSV', icon: FileUp },
  { href: '/accounts/new', title: 'Adicionar conta', text: 'Manual ou por arquivo', icon: Wallet },
  // Copilot é do M11: fica visível e desabilitado, como nos Insights (ADR-0024).
  { href: null, title: 'Perguntar ao Copilot', text: 'Sobre seus dados', icon: Sparkles },
  { href: '/goals/new', title: 'Criar objetivo', text: 'Com previsão de prazo', icon: Target },
]

/** "Ações rápidas" da Home (03-screens "O que posso fazer agora?", D-Home; ADR-0037). Só atalhos, sem dados. */
export function QuickActions() {
  return (
    <section className={styles.card} aria-labelledby="quick-actions-title">
      <h2 id="quick-actions-title" className={styles.title}>Ações rápidas</h2>
      <ul className={styles.list}>
        {ACTIONS.map(({ href, title, text, icon: Icon }) => {
          const body = (
            <>
              <span className={styles.icon} aria-hidden="true"><Icon size={20} strokeWidth={1.75} /></span>
              <span className={styles.text}>
                <span className={styles.name}>{title}</span>
                <span className={styles.hint}>{href ? text : `${text} · Em breve`}</span>
              </span>
              {href ? <ChevronRight size={16} strokeWidth={1.75} className={styles.chevron} aria-hidden="true" /> : null}
            </>
          )
          return (
            <li key={title}>
              {href ? (
                <Link href={href} className={styles.item}>{body}</Link>
              ) : (
                <span className={`${styles.item} ${styles.disabled}`} aria-disabled="true">{body}</span>
              )}
            </li>
          )
        })}
      </ul>
    </section>
  )
}
