import Link from 'next/link'
import { ChevronRight } from 'lucide-react'
import type { OverviewAccount } from '@/lib/api/types'
import { Card } from '@/components/ui/Card'
import { Money } from '@/components/ui/Money'
import { ACCOUNT_TYPE_ICON, accountTypeLabel, FallbackAccountIcon } from './labels'
import styles from './AccountsSummary.module.css'

/** Apenas contexto e acesso: o gerenciamento completo de contas é outra tela. Saldos vêm do backend. */
export function AccountsSummary({ accounts }: { accounts: OverviewAccount[] }) {
  return (
    <Card
      id="accounts"
      title="Contas"
      action={
        <Link href="/accounts" className={styles.seeAll}>
          Ver contas <ChevronRight size={16} aria-hidden="true" />
        </Link>
      }
    >
      <ul className={styles.list}>
        {accounts.map((account) => {
          const Icon = ACCOUNT_TYPE_ICON[account.type] ?? FallbackAccountIcon
          return (
            <li key={account.id}>
              <Link href={`/accounts/${account.id}`} className={styles.item}>
                <span className={styles.tile} aria-hidden="true">
                  <Icon size={20} strokeWidth={1.75} />
                </span>
                <span className={styles.text}>
                  <span className={styles.name}>{account.name}</span>
                  <span className={styles.meta}>
                    {[accountTypeLabel(account.type), account.institutionName].filter(Boolean).join(' · ')}
                  </span>
                  {!account.includedInTotal ? <span className={styles.badge}>Fora do saldo total</span> : null}
                </span>
                <span className={styles.balance}>
                  <Money value={account.balance} />
                </span>
              </Link>
            </li>
          )
        })}
      </ul>
    </Card>
  )
}
