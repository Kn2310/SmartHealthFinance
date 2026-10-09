'use client'

import { Archive, ChevronLeft, CircleAlert, Download, ListX, RotateCcw, SearchX } from 'lucide-react'
import Link from 'next/link'
import { useState } from 'react'
import { Button } from '@/components/ui/Button'
import { Card } from '@/components/ui/Card'
import { Money } from '@/components/ui/Money'
import { Skeleton } from '@/components/ui/Skeleton'
import { accountTypeLabel } from '@/features/home/labels'
import { formatShortDate } from '@/lib/format/date'
import { AccountForm } from './AccountForm'
import { archiveAccount, reactivateAccount, updateAccount, type Account, type AccountFields } from './api'
import { useAccount, useBalances } from './hooks'
import { requestFailureMessage } from './messages'
import { Monogram, StatusBadge } from './parts'
import styles from './Accounts.module.css'

const BACK = (
  <Link href="/accounts" className={styles.back}>
    <ChevronLeft size={18} strokeWidth={1.75} aria-hidden="true" />
    Contas
  </Link>
)

/**
 * D-AccountDetail + D-EditAccount (como seção da página): dados da conta, saldo (do Overview), edição e
 * arquivar/reativar. `id` que não é UUID nem chega ao BFF: é o mesmo "não encontrada" de outro Workspace.
 */
export function AccountDetailView({ id }: { id: string | null }) {
  if (!id) return <NotFound />
  return <AccountDetail id={id} />
}

function NotFound() {
  return (
    <div className={styles.narrow}>
      {BACK}
      <section className={styles.empty} aria-labelledby="not-found-title">
        <span className={styles.stateIcon} aria-hidden="true">
          <SearchX size={28} strokeWidth={1.75} />
        </span>
        <h1 id="not-found-title" className={styles.sectionTitle}>
          Conta não encontrada
        </h1>
        <p className={styles.muted}>Esta conta não existe ou não está disponível para você. Nenhum dado foi alterado.</p>
        <Button href="/accounts">Ver minhas contas</Button>
      </section>
    </div>
  )
}

function AccountDetail({ id }: { id: string }) {
  const account = useAccount(id)

  if (account.status === 'loading') {
    return (
      <div className={styles.narrow}>
        {BACK}
        <div className={styles.panel} role="status" aria-busy="true">
          <span className="sr-only">Carregando a conta…</span>
          <Skeleton width="md" height="title" />
          <Skeleton width="sm" height="caption" />
          <Skeleton height="row" />
          <Skeleton height="row" />
        </div>
      </div>
    )
  }

  if (account.status === 'error') {
    if (account.failure.code === 'ACCOUNT_NOT_FOUND') return <NotFound />
    return (
      <div className={styles.narrow}>
        {BACK}
        <section className={`${styles.panel} ${styles.danger}`} role="alert" aria-labelledby="account-error-title">
          <span className={styles.stateIcon} aria-hidden="true">
            <CircleAlert size={24} strokeWidth={1.75} />
          </span>
          <h1 id="account-error-title" className={styles.sectionTitle}>
            Não foi possível carregar esta conta
          </h1>
          <p className={styles.muted}>{requestFailureMessage(account.failure.code)}</p>
          <Button onClick={account.retry}>Tentar novamente</Button>
        </section>
      </div>
    )
  }

  // A cada recarga (ex.: depois de um conflito) o formulário recomeça com os dados do servidor.
  return <Loaded key={account.attempt} account={account.data} onChange={account.replace} onReload={account.retry} />
}

function Loaded({
  account,
  onChange,
  onReload,
}: {
  account: Account
  onChange: (account: Account) => void
  onReload: () => void
}) {
  const balances = useBalances()
  const [notice, setNotice] = useState<string | null>(null)
  const [actionError, setActionError] = useState<{ code: string } | null>(null)
  const [busy, setBusy] = useState(false)
  const archived = account.status === 'ARCHIVED'

  async function save(fields: AccountFields) {
    setNotice(null)
    setActionError(null)
    const result = await updateAccount(account.id, fields)
    if (!result.ok) {
      // Estado da conta mudou no servidor: a página explica e oferece recarregar; o resto é erro do formulário.
      if (result.code === 'CONFLICT' || result.code === 'ACCOUNT_ARCHIVED') {
        setActionError({ code: result.code })
        return null
      }
      if (result.code === 'ACCOUNT_NOT_FOUND') {
        onReload()
        return null
      }
      return result
    }
    onChange(result.data)
    setNotice('Alterações salvas.')
    return null
  }

  async function changeStatus(action: 'archive' | 'reactivate') {
    if (busy) return
    setBusy(true)
    setNotice(null)
    setActionError(null)
    const result = action === 'archive' ? await archiveAccount(account.id) : await reactivateAccount(account.id)
    setBusy(false)
    if (!result.ok) {
      if (result.code === 'ACCOUNT_NOT_FOUND') return onReload()
      return setActionError({ code: result.code })
    }
    onChange(result.data)
    setNotice(
      action === 'archive'
        ? 'Conta arquivada. O histórico foi mantido e você pode reativá-la quando quiser.'
        : 'Conta reativada. Ela volta a receber movimentações e ao saldo total.',
    )
  }

  const fields: AccountFields = {
    name: account.name,
    type: account.type,
    institutionName: account.institutionName,
    includedInTotal: account.includedInTotal,
  }

  return (
    <div className={styles.narrow}>
      {BACK}
      <header className={styles.detailHeader}>
        <Monogram name={account.institutionName ?? account.name} />
        <div className={styles.detailTitle}>
          <h1 className={styles.pageTitle}>{account.name}</h1>
          <p className={styles.meta}>{[accountTypeLabel(account.type), account.institutionName].filter(Boolean).join(' · ')}</p>
          <div className={styles.badges}>
            <StatusBadge status={account.status} />
            {!archived && !account.includedInTotal ? <span className={`${styles.badge} ${styles.neutral}`}>Fora do saldo total</span> : null}
          </div>
        </div>
        {archived ? null : (
          <Button href={`/import?accountId=${account.id}`} variant="secondary">
            <Download size={18} strokeWidth={1.75} aria-hidden="true" />
            Importar extrato
          </Button>
        )}
      </header>

      <div className={styles.live} role="status">
        {notice}
      </div>
      {actionError ? (
        <div className={styles.error} role="alert">
          <CircleAlert size={18} strokeWidth={1.75} aria-hidden="true" />
          <div className={styles.errorBody}>
            <span>{requestFailureMessage(actionError.code)}</span>
            {actionError.code === 'CONFLICT' || actionError.code === 'ACCOUNT_ARCHIVED' ? (
              <Button variant="secondary" onClick={onReload}>
                Recarregar dados
              </Button>
            ) : null}
          </div>
        </div>
      ) : null}

      {archived ? (
        <section className={styles.archivedNotice} aria-labelledby="archived-title">
          <h2 id="archived-title" className={styles.sectionTitle}>
            Conta arquivada
          </h2>
          <p className={styles.muted}>
            O histórico continua guardado, mas a conta não entra no saldo total, não recebe movimentações e não pode ser
            editada. Reative para voltar a usá-la.
          </p>
          <Button onClick={() => changeStatus('reactivate')} disabled={busy}>
            <RotateCcw size={18} strokeWidth={1.75} aria-hidden="true" />
            {busy ? 'Reativando…' : 'Reativar conta'}
          </Button>
        </section>
      ) : (
        <section className={styles.summary} aria-labelledby="balance-title">
          <div className={styles.summaryMain}>
            <h2 id="balance-title" className={styles.summaryLabel}>
              {balances.status === 'success' ? `Saldo em ${formatShortDate(balances.data.asOf)}` : 'Saldo'}
            </h2>
            <p className={styles.summaryValue}>
              {balances.status === 'loading' ? (
                <Skeleton width="md" height="value" />
              ) : (
                <Money value={balances.status === 'success' ? (balances.data.byAccount.get(account.id) ?? null) : null} />
              )}
            </p>
          </div>
          {balances.status === 'error' ? (
            <p className={styles.partial}>Não conseguimos carregar o saldo agora. Os dados da conta continuam disponíveis.</p>
          ) : (
            <p className={styles.muted}>Calculado a partir das movimentações lançadas nesta conta.</p>
          )}
        </section>
      )}

      <Card id="edit-account" title={archived ? 'Dados da conta' : 'Editar conta'}>
        <AccountForm
          initial={fields}
          submitLabel="Salvar alterações"
          submittingLabel="Salvando…"
          onSubmit={save}
          readOnly={archived}
        />
      </Card>

      {archived ? null : (
        <section className={styles.panel} aria-labelledby="archive-title">
          <h2 id="archive-title" className={styles.sectionTitle}>
            Arquivar conta
          </h2>
          <p className={styles.muted}>
            O histórico é mantido e você pode reativar depois. Contas arquivadas saem do saldo total e não recebem
            movimentações.
          </p>
          <div>
            <Button variant="secondary" onClick={() => changeStatus('archive')} disabled={busy}>
              <Archive size={18} strokeWidth={1.75} aria-hidden="true" />
              {busy ? 'Arquivando…' : 'Arquivar conta'}
            </Button>
          </div>
        </section>
      )}

      <section className={styles.unavailablePanel} aria-labelledby="movements-title">
        <span className={styles.unavailableIcon} aria-hidden="true">
          <ListX size={20} strokeWidth={1.75} />
        </span>
        <div>
          <h2 id="movements-title" className={styles.sectionTitle}>
            Movimentações <span className={styles.soonBadge}>Em breve</span>
          </h2>
          <p className={styles.muted}>
            A lista de movimentações por conta chega em uma próxima versão. As mais recentes já aparecem no Início.
          </p>
        </div>
      </section>
    </div>
  )
}
