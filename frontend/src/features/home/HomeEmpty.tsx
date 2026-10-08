import { CalendarX2, CircleHelp, Wallet } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { formatShortDate } from '@/lib/format/date'
import { plural } from './labels'
import styles from './HomeEmpty.module.css'

/** NO_ACCOUNTS: ainda não existe nenhuma conta. Não há dashboard, só o próximo passo. */
export function NoAccounts() {
  return (
    <section className={styles.empty} aria-labelledby="empty-title">
      <span className={styles.icon} aria-hidden="true">
        <Wallet size={32} strokeWidth={1.75} />
      </span>
      <h2 id="empty-title" className={styles.title}>
        Você ainda não tem contas
      </h2>
      <p className={styles.text}>Adicione sua primeira conta para acompanhar seu saldo e suas movimentações em um só lugar.</p>
      <Button href="/accounts/new">Adicionar conta</Button>
    </section>
  )
}

/** NO_TRANSACTIONS: há contas, mas nenhuma movimentação lançada. Sem saldo para mostrar (≠ R$ 0,00). */
export function NoTransactions({ accountCount, pendingTransactions }: { accountCount: number; pendingTransactions: number }) {
  return (
    <section className={styles.empty} aria-labelledby="empty-title">
      <span className={styles.icon} aria-hidden="true">
        <Wallet size={32} strokeWidth={1.75} />
      </span>
      <h2 id="empty-title" className={styles.title}>
        Registre sua primeira movimentação
      </h2>
      <p className={styles.text}>
        {plural(accountCount, 'conta adicionada', 'contas adicionadas')}. Seu saldo e seu fluxo de caixa aparecem aqui assim que houver
        movimentações lançadas.
        {pendingTransactions > 0
          ? ` ${plural(pendingTransactions, 'movimentação pendente aparece', 'movimentações pendentes aparecem')} abaixo e só ${
              pendingTransactions === 1 ? 'entra' : 'entram'
            } no saldo depois de lançada${pendingTransactions === 1 ? '' : 's'}.`
          : ''}
      </p>
      <div className={styles.actions}>
        <Button href="/import">Importar extrato</Button>
        <Button href="/transactions/new" variant="secondary">
          Registrar movimentação
        </Button>
      </div>
    </section>
  )
}

/** NO_ACTIVITY_IN_PERIOD: existe histórico; só não houve movimentação no período escolhido. */
export function NoActivityInPeriod({ balanceAsOf }: { balanceAsOf: string }) {
  return (
    <section className={`${styles.empty} ${styles.fill}`} aria-labelledby="no-activity-title">
      <span className={styles.icon} aria-hidden="true">
        <CalendarX2 size={24} strokeWidth={1.75} />
      </span>
      <h2 id="no-activity-title" className={styles.title}>
        Nenhuma movimentação neste período
      </h2>
      <p className={styles.text}>
        Escolha outro período para ver receitas e despesas. Seu saldo continua refletindo todo o histórico até{' '}
        {formatShortDate(balanceAsOf)}
      </p>
    </section>
  )
}

/** Estado que esta versão do app não conhece (backend mais novo): neutro, sem valores e sem erro de UI. */
export function UnsupportedState() {
  return (
    <section className={styles.empty} aria-labelledby="unsupported-title">
      <span className={styles.icon} aria-hidden="true">
        <CircleHelp size={24} strokeWidth={1.75} />
      </span>
      <h2 id="unsupported-title" className={styles.title}>
        Não conseguimos exibir este resumo
      </h2>
      <p className={styles.text}>Seus dados estão seguros. Atualize a página para carregar a versão mais recente do app.</p>
    </section>
  )
}
