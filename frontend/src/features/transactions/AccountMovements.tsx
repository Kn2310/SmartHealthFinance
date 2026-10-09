'use client'

import { Inbox, Plus } from 'lucide-react'
import { useState } from 'react'
import { Button } from '@/components/ui/Button'
import type { Filters } from './filters'
import { TransactionList } from './TransactionList'
import accountStyles from '@/features/accounts/Accounts.module.css'

/**
 * "Movimentações" de D-AccountDetail: a mesma lista de /transactions, sempre filtrada pela conta (origem ou
 * destino — ADR-0005 §1) e com todo o período por padrão. Os filtros desta seção ficam só no estado da tela.
 */
export function AccountMovements({ accountId, archived }: { accountId: string; archived: boolean }) {
  const [filters, setFilters] = useState<Filters>({ period: 'ALL' })
  const newHref = `/transactions/new?accountId=${accountId}&returnTo=account`

  return (
    <section className={accountStyles.panel} aria-labelledby="movements-title">
      <div className={accountStyles.sectionHeader}>
        <h2 id="movements-title" className={accountStyles.sectionTitle}>
          Movimentações
        </h2>
        {archived ? null : (
          <Button href={newHref}>
            <Plus size={18} strokeWidth={1.75} aria-hidden="true" />
            Nova transação
          </Button>
        )}
      </div>
      <TransactionList
        filters={filters}
        onFiltersChange={setFilters}
        defaultPeriod="ALL"
        fixedAccountId={accountId}
        firstTimeEmpty={
          <section className={accountStyles.empty} aria-labelledby="account-empty-title">
            <span className={accountStyles.stateIcon} aria-hidden="true">
              <Inbox size={24} strokeWidth={1.75} />
            </span>
            <h3 id="account-empty-title" className={accountStyles.sectionTitle}>
              Nenhuma movimentação nesta conta
            </h3>
            <p className={accountStyles.muted}>
              {archived
                ? 'Esta conta está arquivada e não recebe movimentações.'
                : 'Registre receitas, despesas ou um ajuste de saldo, ou importe o extrato desta conta.'}
            </p>
            {archived ? null : (
              <div className={accountStyles.emptyActions}>
                <Button href={newHref}>
                  <Plus size={18} strokeWidth={1.75} aria-hidden="true" />
                  Nova transação
                </Button>
              </div>
            )}
          </section>
        }
      />
    </section>
  )
}
