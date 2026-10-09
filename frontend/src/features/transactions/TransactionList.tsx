'use client'

import { CircleAlert, Inbox, RotateCw, Search } from 'lucide-react'
import { useId, useState, type ReactNode } from 'react'
import { Button } from '@/components/ui/Button'
import { Skeleton } from '@/components/ui/Skeleton'
import { TRANSACTION_SEARCH_MAX_LENGTH } from '@/lib/api/contract'
import { formatDayHeading, formatPeriodRange } from '@/lib/format/date'
import { businessToday } from '@/lib/format/business-date'
import type { Transaction, TransactionStatus, TransactionType } from './api'
import { isFiltered, PERIOD_OPTIONS, periodRange, toListParams, type Filters, type PeriodPreset } from './filters'
import { accountNames, useAccounts, useTransactionPages } from './hooks'
import { STATUS_OPTIONS, TYPE_OPTIONS } from './labels'
import { plural, requestFailureMessage } from './messages'
import { TransactionRow } from './TransactionRow'
import accountStyles from '@/features/accounts/Accounts.module.css'
import styles from './Transactions.module.css'

interface Props {
  filters: Filters
  onFiltersChange: (next: Filters) => void
  defaultPeriod: PeriodPreset
  /** Página da conta: filtra sempre por ela e esconde o seletor de conta. */
  fixedAccountId?: string
  /** Conteúdo do estado vazio "primeira vez" (nenhuma transação, nenhum filtro). */
  firstTimeEmpty: ReactNode
}

/** Agrupa por dia mantendo a ordem do backend (mais recentes primeiro). */
function byDay(items: Transaction[]): [string, Transaction[]][] {
  const groups: [string, Transaction[]][] = []
  for (const item of items) {
    const last = groups[groups.length - 1]
    if (last && last[0] === item.occurredOn) last[1].push(item)
    else groups.push([item.occurredOn, [item]])
  }
  return groups
}

/**
 * Lista de transações com filtros (período, tipo, status, conta e busca) — D-Transactions e a seção
 * "Movimentações" de D-AccountDetail. Loading, vazio (primeira vez × filtros sem resultado) e erro (D-TxnError).
 */
export function TransactionList({ filters, onFiltersChange, defaultPeriod, fixedAccountId, firstTimeEmpty }: Props) {
  const id = useId()
  const [today] = useState(businessToday)
  const [draftQuery, setDraftQuery] = useState('')
  const [query, setQuery] = useState('')
  const effective: Filters = fixedAccountId ? { ...filters, accountId: fixedAccountId } : filters
  const pages = useTransactionPages(toListParams(effective, query, today))
  const accounts = useAccounts()
  const names = accountNames(accounts)
  const range = periodRange(effective, today)
  const filtered = isFiltered(effective, query, defaultPeriod, Boolean(fixedAccountId))

  const [custom, setCustom] = useState({ from: range.from ?? '', to: range.to ?? '' })
  const customInvalid = !custom.from || !custom.to || custom.to < custom.from

  const set = (patch: Partial<Filters>) => onFiltersChange({ ...filters, ...patch })

  function changePeriod(period: PeriodPreset) {
    if (period !== 'CUSTOM') return set({ period, from: undefined, to: undefined })
    // Personalizado começa no intervalo que está na tela (ou no mês corrente); a pessoa ajusta e aplica.
    const start = range.from && range.to ? { from: range.from, to: range.to } : periodRange({ period: 'CURRENT_MONTH' }, today)
    setCustom({ from: start.from!, to: start.to! })
    set({ period, from: start.from, to: start.to })
  }

  function clearFilters() {
    setDraftQuery('')
    setQuery('')
    onFiltersChange({ period: defaultPeriod })
  }

  const periodText = range.from && range.to ? formatPeriodRange(range.from, range.to) : 'Todo o período'

  return (
    <div className={accountStyles.section}>
      <form
        className={styles.filters}
        role="search"
        aria-label="Filtrar transações"
        onSubmit={(event) => {
          event.preventDefault()
          setQuery(draftQuery.trim())
        }}
      >
        <div className={styles.search}>
          <div className={styles.searchField}>
            <label htmlFor={`${id}-q`} className="sr-only">
              Buscar na descrição
            </label>
            <Search size={20} strokeWidth={1.75} className={styles.searchIcon} aria-hidden="true" />
            <input
              id={`${id}-q`}
              type="search"
              className={`${accountStyles.input} ${styles.searchInput}`}
              placeholder={fixedAccountId ? 'Buscar nesta conta' : 'Buscar na descrição'}
              value={draftQuery}
              maxLength={TRANSACTION_SEARCH_MAX_LENGTH}
              onChange={(event) => {
                setDraftQuery(event.target.value)
                // Apagar a busca já volta a lista inteira, sem precisar enviar.
                if (event.target.value.trim() === '' && query) setQuery('')
              }}
              autoComplete="off"
            />
          </div>
          <Button type="submit" variant="secondary">
            Buscar
          </Button>
        </div>

        <div className={`${styles.selects} ${fixedAccountId ? styles.three : ''}`}>
          <div className={accountStyles.field}>
            <label htmlFor={`${id}-period`} className={accountStyles.label}>
              Período
            </label>
            <select
              id={`${id}-period`}
              className={accountStyles.select}
              value={filters.period}
              onChange={(event) => changePeriod(event.target.value as PeriodPreset)}
            >
              {PERIOD_OPTIONS.map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </div>
          <div className={accountStyles.field}>
            <label htmlFor={`${id}-type`} className={accountStyles.label}>
              Tipo
            </label>
            <select
              id={`${id}-type`}
              className={accountStyles.select}
              value={filters.type ?? ''}
              onChange={(event) => set({ type: (event.target.value || undefined) as TransactionType | undefined })}
            >
              <option value="">Todos</option>
              {TYPE_OPTIONS.map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </div>
          <div className={accountStyles.field}>
            <label htmlFor={`${id}-status`} className={accountStyles.label}>
              Status
            </label>
            <select
              id={`${id}-status`}
              className={accountStyles.select}
              value={filters.status ?? ''}
              onChange={(event) => set({ status: (event.target.value || undefined) as TransactionStatus | undefined })}
            >
              <option value="">Todos</option>
              {STATUS_OPTIONS.map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </div>
          {fixedAccountId ? null : (
            <div className={accountStyles.field}>
              <label htmlFor={`${id}-account`} className={accountStyles.label}>
                Conta
              </label>
              <select
                id={`${id}-account`}
                className={accountStyles.select}
                value={filters.accountId ?? ''}
                onChange={(event) => set({ accountId: event.target.value || undefined })}
              >
                <option value="">Todas</option>
                {accounts.status === 'success'
                  ? accounts.data.map((account) => (
                      <option key={account.id} value={account.id}>
                        {account.status === 'ARCHIVED' ? `${account.name} (arquivada)` : account.name}
                      </option>
                    ))
                  : null}
                {/* Conta da URL ainda sem nome (carregando ou falhou): o filtro continua visível e removível. */}
                {filters.accountId && !names.has(filters.accountId) ? <option value={filters.accountId}>Conta selecionada</option> : null}
              </select>
            </div>
          )}
        </div>

        {filters.period === 'CUSTOM' ? (
          <div className={styles.custom}>
            <div className={accountStyles.field}>
              <label htmlFor={`${id}-from`} className={accountStyles.label}>
                De
              </label>
              <input
                id={`${id}-from`}
                type="date"
                className={accountStyles.input}
                value={custom.from}
                onChange={(event) => setCustom((c) => ({ ...c, from: event.target.value }))}
                aria-invalid={customInvalid ? true : undefined}
                aria-describedby={customInvalid ? `${id}-custom-error` : undefined}
              />
            </div>
            <div className={accountStyles.field}>
              <label htmlFor={`${id}-to`} className={accountStyles.label}>
                Até
              </label>
              <input
                id={`${id}-to`}
                type="date"
                className={accountStyles.input}
                value={custom.to}
                onChange={(event) => setCustom((c) => ({ ...c, to: event.target.value }))}
                aria-invalid={customInvalid ? true : undefined}
                aria-describedby={customInvalid ? `${id}-custom-error` : undefined}
              />
            </div>
            <Button variant="secondary" disabled={customInvalid} onClick={() => set({ period: 'CUSTOM', ...custom })}>
              Aplicar período
            </Button>
            {customInvalid ? (
              <p id={`${id}-custom-error`} className={accountStyles.fieldError}>
                <CircleAlert size={16} strokeWidth={1.75} aria-hidden="true" />
                <span>Informe as duas datas, com a final igual ou depois da inicial.</span>
              </p>
            ) : null}
          </div>
        ) : null}

        {filtered ? (
          <button type="button" className={styles.clear} onClick={clearFilters}>
            Limpar filtros
          </button>
        ) : null}
      </form>

      {accounts.status === 'error' ? (
        <p className={accountStyles.partial}>Não conseguimos carregar os nomes das contas agora. As transações continuam corretas.</p>
      ) : null}

      <p className={styles.count} role="status">
        {pages.first.status === 'success' ? `${periodText} · ${plural(pages.total, 'transação', 'transações')}` : null}
      </p>

      {pages.first.status === 'loading' ? (
        <div className={styles.listSkeleton} role="status" aria-busy="true">
          <span className="sr-only">Carregando transações…</span>
          <Skeleton width="sm" height="caption" />
          <Skeleton height="row" />
          <Skeleton height="row" />
          <Skeleton height="row" />
        </div>
      ) : pages.first.status === 'error' ? (
        <section className={`${accountStyles.panel} ${accountStyles.danger}`} role="alert" aria-labelledby={`${id}-error-title`}>
          <span className={accountStyles.stateIcon} aria-hidden="true">
            <CircleAlert size={24} strokeWidth={1.75} />
          </span>
          <h3 id={`${id}-error-title`} className={accountStyles.sectionTitle}>
            Não foi possível carregar suas transações
          </h3>
          <p className={accountStyles.muted}>{requestFailureMessage(pages.first.failure.code)}</p>
          <div>
            <Button onClick={pages.retry}>
              <RotateCw size={18} strokeWidth={1.75} aria-hidden="true" />
              Tentar novamente
            </Button>
          </div>
        </section>
      ) : pages.items.length === 0 ? (
        filtered ? (
          <section className={accountStyles.empty} aria-labelledby={`${id}-empty-title`}>
            <span className={accountStyles.stateIcon} aria-hidden="true">
              <Inbox size={24} strokeWidth={1.75} />
            </span>
            <h3 id={`${id}-empty-title`} className={accountStyles.sectionTitle}>
              Nenhuma transação encontrada
            </h3>
            <p className={accountStyles.muted}>
              Não há transações com estes filtros{query ? ` e o texto "${query}"` : ''} em {periodText.toLowerCase()}.
            </p>
            <div className={accountStyles.emptyActions}>
              <Button variant="secondary" onClick={clearFilters}>
                Limpar filtros
              </Button>
            </div>
          </section>
        ) : (
          firstTimeEmpty
        )
      ) : (
        <>
          <div className={styles.groups}>
            {byDay(pages.items).map(([day, items]) => (
              <div key={day}>
                <h3 className={styles.dayHeading}>
                  <time dateTime={day}>{formatDayHeading(day)}</time>
                </h3>
                <ul className={styles.list}>
                  {items.map((transaction) => (
                    <TransactionRow
                      key={transaction.id}
                      transaction={transaction}
                      names={names}
                      perspectiveAccountId={fixedAccountId}
                    />
                  ))}
                </ul>
              </div>
            ))}
          </div>
          {pages.hasMore || pages.moreFailure ? (
            <div className={styles.more}>
              {pages.moreFailure ? (
                <p className={accountStyles.fieldError} role="alert">
                  <CircleAlert size={16} strokeWidth={1.75} aria-hidden="true" />
                  <span>Não conseguimos carregar mais transações. Tente de novo.</span>
                </p>
              ) : null}
              <Button variant="secondary" onClick={pages.loadMore} disabled={pages.loadingMore}>
                {pages.loadingMore ? 'Carregando…' : 'Carregar mais'}
              </Button>
            </div>
          ) : null}
        </>
      )}
    </div>
  )
}
