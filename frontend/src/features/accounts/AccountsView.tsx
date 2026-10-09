'use client'

import { CircleAlert, Download, Info, Plus, Wallet } from 'lucide-react'
import Link from 'next/link'
import { useId, useState } from 'react'
import { Button } from '@/components/ui/Button'
import { Money } from '@/components/ui/Money'
import { Skeleton } from '@/components/ui/Skeleton'
import { accountTypeLabel, plural } from '@/features/home/labels'
import { formatShortDate } from '@/lib/format/date'
import type { Account } from './api'
import { useAllAccounts, useBalances, type Balances } from './hooks'
import { requestFailureMessage } from './messages'
import { Monogram, StatusBadge } from './parts'
import styles from './Accounts.module.css'

/** D-Accounts: quanto você tem e onde está. Saldos vêm do Overview; esta tela só exibe. */
export function AccountsView() {
  const accounts = useAllAccounts()
  const balances = useBalances()

  return (
    <div className={styles.page}>
      <header className={styles.listHeader}>
        <div>
          <h1 className={styles.pageTitle}>Contas</h1>
          <p className={styles.pageSubtitle}>Quanto você tem e onde está o seu dinheiro.</p>
        </div>
        {accounts.status === 'success' && accounts.data.length > 0 ? (
          <Button href="/accounts/new">
            <Plus size={18} strokeWidth={1.75} aria-hidden="true" />
            Adicionar conta
          </Button>
        ) : null}
      </header>

      {accounts.status === 'loading' ? (
        <div className={styles.panel} role="status" aria-busy="true">
          <span className="sr-only">Carregando suas contas…</span>
          <Skeleton width="sm" height="caption" />
          <Skeleton width="md" height="value" />
          <Skeleton height="row" />
          <Skeleton height="row" />
        </div>
      ) : accounts.status === 'error' ? (
        <section className={`${styles.panel} ${styles.danger}`} role="alert" aria-labelledby="accounts-error-title">
          <span className={styles.stateIcon} aria-hidden="true">
            <CircleAlert size={24} strokeWidth={1.75} />
          </span>
          <h2 id="accounts-error-title" className={styles.sectionTitle}>
            Não foi possível carregar suas contas
          </h2>
          <p className={styles.muted}>{requestFailureMessage(accounts.failure.code)}</p>
          <Button onClick={accounts.retry}>Tentar novamente</Button>
        </section>
      ) : accounts.data.length === 0 ? (
        <NoAccounts />
      ) : (
        <AccountList accounts={accounts.data} balances={balances.status === 'success' ? balances.data : null} balancesFailed={balances.status === 'error'} />
      )}
    </div>
  )
}

/** D-AccountsEmpty: primeira vez. Importar também começa criando a conta (a importação precisa de destino). */
function NoAccounts() {
  return (
    <section className={styles.empty} aria-labelledby="no-accounts-title">
      <span className={styles.stateIcon} aria-hidden="true">
        <Wallet size={28} strokeWidth={1.75} />
      </span>
      <h2 id="no-accounts-title" className={styles.sectionTitle}>
        Nenhuma conta adicionada ainda
      </h2>
      <p className={styles.muted}>
        Adicione sua primeira conta para ver quanto você tem e onde está. Para trazer as movimentações de um extrato OFX
        ou CSV, crie a conta e siga para a importação.
      </p>
      <div className={styles.emptyActions}>
        <Button href="/accounts/new?returnTo=import">
          <Download size={18} strokeWidth={1.75} aria-hidden="true" />
          Importar extrato
        </Button>
        <Button href="/accounts/new" variant="secondary">
          Criar conta manual
        </Button>
      </div>
    </section>
  )
}

function AccountList({
  accounts,
  balances,
  balancesFailed,
}: {
  accounts: Account[]
  balances: Balances | null
  balancesFailed: boolean
}) {
  const [showArchived, setShowArchived] = useState(false)
  const toggleId = useId()
  const active = accounts.filter((account) => account.status === 'ACTIVE')
  const archivedCount = accounts.length - active.length
  const shown = showArchived ? accounts : active

  return (
    <>
      <section className={styles.summary} aria-labelledby="total-title">
        <div className={styles.summaryMain}>
          <h2 id="total-title" className={styles.summaryLabel}>
            {balances ? `Saldo total em ${formatShortDate(balances.asOf)}` : 'Saldo total'}
          </h2>
          <p className={styles.summaryValue}>
            <Money value={balances?.total ?? null} />
          </p>
        </div>
        <dl className={styles.summaryStats}>
          <div>
            <dt>Ativas</dt>
            <dd>{active.length}</dd>
          </div>
          <div>
            <dt>Arquivadas</dt>
            <dd>{archivedCount}</dd>
          </div>
        </dl>
        {balancesFailed ? (
          <p className={styles.partial} role="status">
            Não conseguimos carregar os saldos agora. Suas contas aparecem abaixo; tente atualizar a página em instantes.
          </p>
        ) : null}
      </section>

      <aside className={styles.info} aria-labelledby="how-title">
        <Info size={18} strokeWidth={1.75} aria-hidden="true" />
        <div>
          <h2 id="how-title" className={styles.infoTitle}>
            Como calculamos o saldo total
          </h2>
          <p>
            Somamos as movimentações já lançadas de cada conta ativa marcada para entrar no saldo total. Contas arquivadas
            ou fora do total não entram. Faturas de cartão também não entram aqui.
          </p>
        </div>
      </aside>

      <section className={styles.section} aria-labelledby="your-accounts-title">
        <div className={styles.sectionHeader}>
          <h2 id="your-accounts-title" className={styles.sectionTitle}>
            Suas contas
          </h2>
          {archivedCount > 0 ? (
            <label htmlFor={toggleId} className={styles.filter}>
              <input
                id={toggleId}
                type="checkbox"
                className={styles.checkbox}
                checked={showArchived}
                onChange={(event) => setShowArchived(event.target.checked)}
              />
              Mostrar arquivadas ({archivedCount})
            </label>
          ) : null}
        </div>

        {shown.length === 0 ? (
          <div className={styles.filterEmpty}>
            <p className={styles.sectionTitle}>Nenhuma conta ativa</p>
            <p className={styles.muted}>
              {plural(archivedCount, 'conta está arquivada', 'contas estão arquivadas')}. Mostre as arquivadas para
              reativar ou adicione uma nova conta.
            </p>
          </div>
        ) : (
          <ul className={styles.grid}>
            {shown.map((account) => (
              <li key={account.id}>
                <AccountCard account={account} balances={balances} />
              </li>
            ))}
          </ul>
        )}
      </section>
    </>
  )
}

function AccountCard({ account, balances }: { account: Account; balances: Balances | null }) {
  const archived = account.status === 'ARCHIVED'
  const titleId = `account-${account.id}`
  return (
    <article className={styles.card} aria-labelledby={titleId}>
      <div className={styles.cardTop}>
        <Monogram name={account.institutionName ?? account.name} />
        <div className={styles.cardText}>
          <h3 id={titleId} className={styles.cardName}>
            <Link href={`/accounts/${account.id}`} className={styles.cardLink}>
              {account.name}
            </Link>
          </h3>
          <p className={styles.meta}>{[accountTypeLabel(account.type), account.institutionName].filter(Boolean).join(' · ')}</p>
        </div>
      </div>

      <div className={styles.badges}>
        <StatusBadge status={account.status} />
        {!archived && !account.includedInTotal ? <span className={`${styles.badge} ${styles.neutral}`}>Fora do saldo total</span> : null}
      </div>

      <div className={styles.cardBalance}>
        <span className={styles.meta}>Saldo</span>
        {archived ? (
          <span className={styles.muted}>Não entra no saldo</span>
        ) : (
          <span className={styles.balanceValue}>
            <Money value={balances?.byAccount.get(account.id) ?? null} />
          </span>
        )}
      </div>

      <div className={styles.cardActions}>
        {archived ? (
          <Button href={`/accounts/${account.id}`} variant="secondary">
            Ver conta <span className="sr-only">{account.name}</span>
          </Button>
        ) : (
          <Button href={`/import?accountId=${account.id}`} variant="secondary">
            <Download size={18} strokeWidth={1.75} aria-hidden="true" />
            Importar extrato <span className="sr-only">para {account.name}</span>
          </Button>
        )}
      </div>
    </article>
  )
}
