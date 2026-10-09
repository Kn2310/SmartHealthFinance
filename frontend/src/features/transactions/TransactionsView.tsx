'use client'

import { ChevronLeft, Inbox, Plus, Upload } from 'lucide-react'
import Link from 'next/link'
import { usePathname, useRouter, useSearchParams } from 'next/navigation'
import { Button } from '@/components/ui/Button'
import { filtersToSearch, parseFilters, type Filters } from './filters'
import { registeredNotice } from './return-to'
import { TransactionList } from './TransactionList'
import accountStyles from '@/features/accounts/Accounts.module.css'

const DEFAULT_PERIOD = 'CURRENT_MONTH' as const

/** D-Transactions: filtros na URL (sem a busca por texto), lista e atalhos para lançar ou importar. */
export function TransactionsView() {
  const router = useRouter()
  const pathname = usePathname()
  const searchParams = useSearchParams()
  const filters = parseFilters(searchParams, DEFAULT_PERIOD)
  const notice = registeredNotice(searchParams.get('registered'))

  function change(next: Filters) {
    const search = filtersToSearch(next, DEFAULT_PERIOD)
    router.replace(search ? `${pathname}?${search}` : pathname, { scroll: false })
  }

  const newHref = filters.accountId ? `/transactions/new?accountId=${filters.accountId}` : '/transactions/new'

  return (
    <div className={accountStyles.page}>
      <Link href="/home" className={accountStyles.back}>
        <ChevronLeft size={18} strokeWidth={1.75} aria-hidden="true" />
        Início
      </Link>
      <header className={accountStyles.listHeader}>
        <div>
          <h1 className={accountStyles.pageTitle}>Transações</h1>
          <p className={accountStyles.pageSubtitle}>Receitas, despesas e ajustes de todas as suas contas.</p>
        </div>
        <div className={accountStyles.emptyActions}>
          <Button href="/import" variant="secondary">
            <Upload size={18} strokeWidth={1.75} aria-hidden="true" />
            Importar extrato
          </Button>
          <Button href={newHref}>
            <Plus size={18} strokeWidth={1.75} aria-hidden="true" />
            Nova transação
          </Button>
        </div>
      </header>

      <div className={accountStyles.live} role="status">
        {notice}
      </div>

      <section className={accountStyles.panel} aria-labelledby="transactions-list-title">
        <h2 id="transactions-list-title" className="sr-only">
          Lista de transações
        </h2>
        <TransactionList
          filters={filters}
          onFiltersChange={change}
          defaultPeriod={DEFAULT_PERIOD}
          firstTimeEmpty={
            <section className={accountStyles.empty} aria-labelledby="first-time-title">
              <span className={accountStyles.stateIcon} aria-hidden="true">
                <Inbox size={24} strokeWidth={1.75} />
              </span>
              <h3 id="first-time-title" className={accountStyles.sectionTitle}>
                Nenhuma transação neste mês
              </h3>
              <p className={accountStyles.muted}>
                Registre suas receitas e despesas ou importe um extrato OFX ou CSV do seu banco. O saldo da Home é
                calculado a partir delas.
              </p>
              <div className={accountStyles.emptyActions}>
                <Button href={newHref}>
                  <Plus size={18} strokeWidth={1.75} aria-hidden="true" />
                  Nova transação
                </Button>
                <Button href="/import" variant="secondary">
                  <Upload size={18} strokeWidth={1.75} aria-hidden="true" />
                  Importar extrato
                </Button>
              </div>
            </section>
          }
        />
      </section>
    </div>
  )
}
