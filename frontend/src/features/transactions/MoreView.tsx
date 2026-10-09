import { ArrowLeftRight, ChevronRight, Lightbulb, Target, User, Wallet, type LucideIcon } from 'lucide-react'
import Link from 'next/link'
import { Button } from '@/components/ui/Button'
import accountStyles from '@/features/accounts/Accounts.module.css'
import styles from './Transactions.module.css'

const ITEMS: { href: string; label: string; icon: LucideIcon; soon?: boolean }[] = [
  { href: '/insights', label: 'Insights', icon: Lightbulb, soon: true },
  { href: '/goals', label: 'Objetivos', icon: Target, soon: true },
  { href: '/accounts', label: 'Contas', icon: Wallet },
  { href: '/transactions', label: 'Transações', icon: ArrowLeftRight },
]

/**
 * M-More mínimo (design/specs/04-navigation-flows: Mais → Insights, Objetivos, Contas, Transações, Perfil). Itens
 * ainda inexistentes levam ao estado "Em breve" e são sinalizados aqui. Sem área principal nova.
 */
export function MoreView() {
  return (
    <div className={accountStyles.narrow}>
      <h1 className={accountStyles.pageTitle}>Mais</h1>
      <nav className={accountStyles.panel} aria-label="Mais seções">
        <ul className={styles.menu}>
          {ITEMS.map(({ href, label, icon: Icon, soon }) => (
            <li key={href} className={styles.menuItem}>
              <Link href={href} className={styles.menuLink}>
                <span className={`${styles.tile} ${styles.neutral}`} aria-hidden="true">
                  <Icon size={20} strokeWidth={1.75} />
                </span>
                <span className={styles.menuLabel}>{label}</span>
                {soon ? <span className={accountStyles.soonBadge}>Em breve</span> : null}
                <ChevronRight size={18} className={styles.menuChevron} aria-hidden="true" />
              </Link>
            </li>
          ))}
        </ul>
      </nav>
      <section className={accountStyles.panel} aria-labelledby="profile-title">
        <h2 id="profile-title" className={accountStyles.sectionTitle}>
          Perfil
        </h2>
        <ul className={styles.menu}>
          <li className={styles.menuItem}>
            <Link href="/profile" className={styles.menuLink}>
              <span className={`${styles.tile} ${styles.neutral}`} aria-hidden="true">
                <User size={20} strokeWidth={1.75} />
              </span>
              <span className={styles.menuLabel}>Perfil e configurações</span>
              <span className={accountStyles.soonBadge}>Em breve</span>
              <ChevronRight size={18} className={styles.menuChevron} aria-hidden="true" />
            </Link>
          </li>
        </ul>
        <form action="/auth/logout" method="post">
          <Button type="submit" variant="secondary">
            Sair
          </Button>
        </form>
      </section>
    </div>
  )
}
