import type { Overview } from '@/lib/api/types'
import { formatShortDate } from '@/lib/format/date'
import { Money } from '@/components/ui/Money'
import { plural } from './labels'
import styles from './BalanceHero.module.css'

/**
 * Responde "quanto eu tenho?" antes de qualquer outro detalhe. O saldo vem pronto do backend.
 * `balanceAsOf` é a data-base do saldo (fim do período ou hoje), não um horário de atualização: daí "Saldo em".
 */
export function BalanceHero({ summary }: { summary: Overview['summary'] }) {
  const pending = summary.pendingTransactions

  return (
    <section className={styles.hero} aria-labelledby="balance-title">
      <h2 id="balance-title" className={styles.label}>
        Saldo total
      </h2>
      <p className={styles.value}>
        <Money value={summary.totalBalance} />
      </p>
      <p className={styles.caption}>
        Saldo em {formatShortDate(summary.balanceAsOf)} · {plural(summary.accountCount, 'conta', 'contas')}
      </p>
      {pending > 0 ? (
        <p className={styles.note}>
          {plural(pending, 'movimentação pendente', 'movimentações pendentes')} no período
          {pending === 1 ? ' ainda não entra' : ' ainda não entram'} no saldo.
        </p>
      ) : null}
    </section>
  )
}
