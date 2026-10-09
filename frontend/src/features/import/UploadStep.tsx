'use client'

import { CircleAlert, Landmark, PiggyBank, Wallet } from 'lucide-react'
import { useRouter } from 'next/navigation'
import { useId, useRef, useState } from 'react'
import { Button } from '@/components/ui/Button'
import { Skeleton } from '@/components/ui/Skeleton'
import { uploadStatement, type Account } from './api'
import { Dropzone } from './Dropzone'
import { checkFile } from './file-check'
import { useAccounts } from './hooks'
import { fileRejectionMessage, requestFailureMessage } from './messages'
import { Steps } from './Steps'
import styles from './Import.module.css'

const ACCOUNT_ICON: Partial<Record<string, typeof Landmark>> = { CHECKING: Landmark, SAVINGS: PiggyBank }
const ACCOUNT_TYPE: Partial<Record<string, string>> = {
  CHECKING: 'Conta corrente',
  SAVINGS: 'Poupança',
  PAYMENT: 'Conta de pagamento',
}

/** Etapa 1: conta de destino + arquivo. O envio só lê o arquivo e monta o preview; nada vira transação. */
export function UploadStep({ preselectedAccountId }: { preselectedAccountId: string | null }) {
  const accounts = useAccounts()

  return (
    <>
      <Steps current={1} />
      {accounts.status === 'loading' ? (
        <div className={styles.panel} role="status" aria-busy="true">
          <span className="sr-only">Carregando suas contas…</span>
          <Skeleton width="sm" height="body" />
          <Skeleton height="row" />
          <Skeleton height="row" />
        </div>
      ) : accounts.status === 'error' ? (
        <section className={styles.notice} role="alert" aria-labelledby="accounts-error-title">
          <h2 id="accounts-error-title" className={styles.noticeTitle}>
            Não foi possível carregar suas contas
          </h2>
          <p className={styles.noticeText}>{requestFailureMessage(accounts.failure.code)}</p>
          <Button onClick={accounts.retry}>Tentar novamente</Button>
        </section>
      ) : accounts.data.length === 0 ? (
        <section className={styles.empty} aria-labelledby="no-accounts-title">
          <span className={styles.emptyIcon} aria-hidden="true">
            <Wallet size={24} strokeWidth={1.75} />
          </span>
          <h2 id="no-accounts-title" className={styles.noticeTitle}>
            Adicione uma conta antes de importar
          </h2>
          <p className={styles.noticeText}>As movimentações do extrato entram em uma conta sua. Crie a conta e volte aqui.</p>
          <Button href="/accounts/new?returnTo=import">Adicionar conta</Button>
        </section>
      ) : (
        <UploadForm
          accounts={accounts.data}
          initialAccountId={
            accounts.data.find((a) => a.id === preselectedAccountId)?.id ??
            (accounts.data.length === 1 ? accounts.data[0]!.id : null)
          }
        />
      )}
    </>
  )
}

function UploadForm({ accounts, initialAccountId }: { accounts: Account[]; initialAccountId: string | null }) {
  const router = useRouter()
  const [accountId, setAccountId] = useState(initialAccountId)
  const [file, setFile] = useState<File | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [sending, setSending] = useState(false)
  // Uma Idempotency-Key por intenção (conta + arquivo): reenviar após falha de rede não duplica o preview.
  const intent = useRef<{ accountId: string; file: File; key: string } | null>(null)
  const legendId = useId()

  function chooseFile(next: File | null) {
    setError(null)
    if (!next) return setFile(null)
    const rejection = checkFile(next)
    if (rejection) {
      setFile(null)
      setError(fileRejectionMessage(rejection))
      return
    }
    setFile(next)
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (!accountId || !file || sending) return
    if (!intent.current || intent.current.accountId !== accountId || intent.current.file !== file) {
      intent.current = { accountId, file, key: `web:${crypto.randomUUID()}` }
    }
    setSending(true)
    setError(null)
    const result = await uploadStatement(file, accountId, intent.current.key)
    if (result.ok) {
      router.replace(`/import?id=${result.data.id}`, { scroll: true })
      return
    }
    setSending(false)
    if (result.code === 'FILE_REJECTED') {
      // O arquivo como um todo não serve: o próximo envio é outra intenção.
      intent.current = null
      setError(fileRejectionMessage(result.reason))
    }
    else {
      setError(requestFailureMessage(result.code))
    }
  }

  return (
    <form className={styles.form} onSubmit={submit} aria-busy={sending} noValidate>
      <fieldset className={styles.fieldset} aria-describedby={`${legendId}-hint`}>
        <legend id={legendId} className={styles.legend}>
          Conta de destino
        </legend>
        <p id={`${legendId}-hint`} className={styles.hint}>
          As movimentações do arquivo entram nesta conta.
        </p>
        <div className={styles.accounts}>
          {accounts.map((account) => {
            const Icon = ACCOUNT_ICON[account.type] ?? Wallet
            return (
              <label key={account.id} className={styles.account}>
                <input
                  type="radio"
                  name="accountId"
                  value={account.id}
                  checked={accountId === account.id}
                  onChange={() => setAccountId(account.id)}
                  className={styles.radio}
                />
                <span className={styles.accountIcon} aria-hidden="true">
                  <Icon size={20} strokeWidth={1.75} />
                </span>
                <span className={styles.accountText}>
                  <span className={styles.accountName}>{account.name}</span>
                  <span className={styles.accountMeta}>
                    {[account.institutionName, ACCOUNT_TYPE[account.type] ?? 'Conta'].filter(Boolean).join(' · ')}
                  </span>
                </span>
              </label>
            )
          })}
        </div>
      </fieldset>

      <Dropzone file={file} onFile={chooseFile} disabled={sending} />

      {error ? (
        <p className={styles.error} role="alert">
          <CircleAlert size={18} strokeWidth={1.75} aria-hidden="true" />
          <span>{error}</span>
        </p>
      ) : null}

      <div className={styles.actions}>
        <Button href="/home" variant="secondary">
          Cancelar
        </Button>
        <Button type="submit" disabled={!accountId || !file || sending}>
          {sending ? 'Lendo o arquivo…' : 'Continuar'}
        </Button>
      </div>
    </form>
  )
}
