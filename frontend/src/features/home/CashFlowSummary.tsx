import { ArrowDownLeft, ArrowLeftRight, ArrowUpRight } from 'lucide-react'
import type { Overview } from '@/lib/api/types'
import { formatPeriodRange } from '@/lib/format/date'
import { Card } from '@/components/ui/Card'
import { Money } from '@/components/ui/Money'
import { plural } from './labels'
import styles from './CashFlowSummary.module.css'

/**
 * Receitas, despesas e resultado líquido vêm calculados do backend (ADR-0006 §9). Reembolso e transferência
 * ficam semanticamente separados: reembolso não é receita; transferência não é receita nem despesa.
 * Estrutura: cada par termo/valor é `dl > div > (dt, dd)`; a explicação do termo faz parte do próprio `dt`.
 */
export function CashFlowSummary({ cashFlow, period }: { cashFlow: Overview['cashFlow']; period: Overview['period'] }) {
  const transfers = cashFlow?.transfers

  return (
    <Card id="cashflow" title="Fluxo do período" subtitle={formatPeriodRange(period.from, period.to)}>
      <dl className={styles.headline}>
        <div className={styles.metric}>
          <dt className={styles.term}>
            <ArrowDownLeft size={16} aria-hidden="true" /> Receitas
          </dt>
          <dd className={styles.big}>
            <Money value={cashFlow?.income} sign="inflow" />
          </dd>
        </div>
        <div className={styles.metric}>
          <dt className={styles.term}>
            <ArrowUpRight size={16} aria-hidden="true" /> Despesas
          </dt>
          <dd className={styles.big}>
            <Money value={cashFlow?.expense} sign="outflow" />
          </dd>
        </div>
      </dl>

      <dl className={styles.rows}>
        <div className={styles.row}>
          <dt className={styles.rowTerm}>
            <span className={styles.rowLabel}>Reembolsos</span>
            <span className={styles.hint}>Devolução de despesas; não contam como receita.</span>
          </dt>
          <dd className={styles.rowValue}>
            <Money value={cashFlow?.refunds} sign="inflow" />
          </dd>
        </div>
        <div className={`${styles.row} ${styles.net}`}>
          <dt className={styles.rowTerm}>
            <span className={styles.rowLabel}>Resultado líquido</span>
            <span className={styles.hint}>Receitas + reembolsos − despesas.</span>
          </dt>
          <dd className={styles.rowValue}>
            <Money value={cashFlow?.net} sign="always" />
          </dd>
        </div>
      </dl>

      <div className={styles.transfers}>
        <ArrowLeftRight size={20} aria-hidden="true" />
        <div className={styles.transfersText}>
          <p className={styles.rowLabel}>Transferências entre contas</p>
          <p className={styles.hint}>
            {transfers && transfers.count > 0 ? (
              <>
                {plural(transfers.count, 'transferência', 'transferências')} · <Money value={transfers.volume} sign="never" />. Não
                contam como receita nem despesa.
              </>
            ) : (
              'Nenhuma transferência no período. Elas não contam como receita nem despesa.'
            )}
          </p>
        </div>
      </div>
    </Card>
  )
}
