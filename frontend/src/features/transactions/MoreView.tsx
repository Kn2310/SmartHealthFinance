import {
  ArrowLeftRight,
  Building2,
  ChevronRight,
  FileCheck,
  Lightbulb,
  Link2,
  Repeat,
  ShieldCheck,
  SlidersHorizontal,
  Target,
  Wallet,
  type LucideIcon,
} from 'lucide-react'
import Link from 'next/link'
import { Button } from '@/components/ui/Button'
import { InsightsBadge } from '@/features/insights/InsightsBadge'
import accountStyles from '@/features/accounts/Accounts.module.css'
import styles from './Transactions.module.css'

const ITEMS: { href: string; label: string; icon: LucideIcon; soon?: boolean }[] = [
  { href: '/insights', label: 'Insights', icon: Lightbulb },
  { href: '/goals', label: 'Objetivos', icon: Target },
  { href: '/accounts', label: 'Contas', icon: Wallet },
  { href: '/transactions', label: 'Transações', icon: ArrowLeftRight },
  { href: '/recurring', label: 'Recorrências', icon: Repeat },
]

/** PERFIL do M-More: só Consentimentos existe; os demais seguem "Em breve" (ADR-0038 §9). */
const PROFILE_ITEMS: { href?: string; label: string; icon: LucideIcon }[] = [
  { label: 'Instituições', icon: Building2 },
  { label: 'Conexões', icon: Link2 },
  { href: '/profile/consents', label: 'Consentimentos', icon: FileCheck },
  { label: 'Segurança', icon: ShieldCheck },
  { label: 'Preferências', icon: SlidersHorizontal },
]

/**
 * M-More (design/specs/04-navigation-flows: Mais → Insights com "N importantes", Objetivos, Contas, Transações, Perfil). Itens
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
                {href === '/insights' ? <InsightsBadge variant="text" className={accountStyles.soonBadge} /> : null}
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
          {PROFILE_ITEMS.map(({ href, label, icon: Icon }) => (
            <li key={label} className={styles.menuItem}>
              {href ? (
                <Link href={href} className={styles.menuLink}>
                  <span className={`${styles.tile} ${styles.neutral}`} aria-hidden="true">
                    <Icon size={20} strokeWidth={1.75} />
                  </span>
                  <span className={styles.menuLabel}>{label}</span>
                  <ChevronRight size={18} className={styles.menuChevron} aria-hidden="true" />
                </Link>
              ) : (
                <span className={styles.menuLink} aria-disabled="true">
                  <span className={`${styles.tile} ${styles.neutral}`} aria-hidden="true">
                    <Icon size={20} strokeWidth={1.75} />
                  </span>
                  <span className={styles.menuLabel}>{label}</span>
                  <span className={accountStyles.soonBadge}>Em breve</span>
                </span>
              )}
            </li>
          ))}
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
