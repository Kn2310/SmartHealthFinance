'use client'

import { CircleAlert, CreditCard, Landmark, PiggyBank, Wallet } from 'lucide-react'
import { useRouter } from 'next/navigation'
import { useId, useRef, useState } from 'react'
import { Button } from '@/components/ui/Button'
import { Skeleton } from '@/components/ui/Skeleton'
import { uploadStatement, type Account, type Card, type Destination } from './api'
import { Dropzone } from './Dropzone'
import { checkFile } from './file-check'
import { useAccounts, useCards } from './hooks'
import { fileRejectionMessage, requestFailureMessage } from './messages'
import { Steps } from './Steps'
import styles from './Import.module.css'

const ACCOUNT_ICON: Partial<Record<string, typeof Landmark>> = { CHECKING: Landmark, SAVINGS: PiggyBank }
const ACCOUNT_TYPE: Partial<Record<string, string>> = {
  CHECKING: 'Conta corrente',
  SAVINGS: 'Poupança',
  PAYMENT: 'Conta de pagamento',
}

const keyOf = (destination: Destination) => `${destination.kind}:${destination.id}`

/**
 * Etapa 1: destino (conta para extrato, cartão para fatura — ADR-0009 §19) + arquivo. O envio só lê o arquivo e
 * monta o preview; nada vira transação.
 */
export function UploadStep({ preselected }: { preselected: Destination | null }) {
  const accounts = useAccounts()
  const cards = useCards()

  if (accounts.status === 'loading' || cards.status === 'loading') {
    return (
      <>
        <Steps current={1} />
        <div className={styles.panel} role="status" aria-busy="true">
          <span className="sr-only">Carregando suas contas e cartões…</span>
          <Skeleton width="sm" height="body" />
          <Skeleton height="row" />
          <Skeleton height="row" />
        </div>
      </>
    )
  }

  const failed = accounts.status === 'error' ? accounts : cards.status === 'error' ? cards : null
  if (failed && failed.status === 'error') {
    return (
      <>
        <Steps current={1} />
        <section className={styles.notice} role="alert" aria-labelledby="accounts-error-title">
          <h2 id="accounts-error-title" className={styles.noticeTitle}>
            Não foi possível carregar suas contas e cartões
          </h2>
          <p className={styles.noticeText}>{requestFailureMessage(failed.failure.code)}</p>
          <Button onClick={failed.retry}>Tentar novamente</Button>
        </section>
      </>
    )
  }

  const accountList = accounts.status === 'success' ? accounts.data : []
  const cardList = cards.status === 'success' ? cards.data : []
  const all: Destination[] = [
    ...accountList.map((a) => ({ kind: 'account' as const, id: a.id })),
    ...cardList.map((c) => ({ kind: 'card' as const, id: c.id })),
  ]
  const initial =
    all.find((d) => preselected && keyOf(d) === keyOf(preselected)) ?? (all.length === 1 ? all[0]! : null)

  return (
    <>
      <Steps current={1} />
      {all.length === 0 ? (
        <section className={styles.empty} aria-labelledby="no-accounts-title">
          <span className={styles.emptyIcon} aria-hidden="true">
            <Wallet size={24} strokeWidth={1.75} />
          </span>
          <h2 id="no-accounts-title" className={styles.noticeTitle}>
            Adicione uma conta antes de importar
          </h2>
          <p className={styles.noticeText}>
            As movimentações do extrato entram em uma conta sua, e as compras da fatura, em um cartão. Crie a conta e
            volte aqui.
          </p>
          <Button href="/accounts/new?returnTo=import">Adicionar conta</Button>
        </section>
      ) : (
        <UploadForm accounts={accountList} cards={cardList} initial={initial} />
      )}
    </>
  )
}

function UploadForm({ accounts, cards, initial }: { accounts: Account[]; cards: Card[]; initial: Destination | null }) {
  const router = useRouter()
  const [destination, setDestination] = useState<Destination | null>(initial)
  const [file, setFile] = useState<File | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [sending, setSending] = useState(false)
  // Uma Idempotency-Key por intenção (destino + arquivo): reenviar após falha de rede não duplica o preview.
  const intent = useRef<{ destination: string; file: File; key: string } | null>(null)
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
    if (!destination || !file || sending) return
    const target = keyOf(destination)
    if (!intent.current || intent.current.destination !== target || intent.current.file !== file) {
      intent.current = { destination: target, file, key: `web:${crypto.randomUUID()}` }
    }
    setSending(true)
    setError(null)
    const result = await uploadStatement(file, destination, intent.current.key)
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

  const choose = (next: Destination) => setDestination(next)
  const selected = destination ? keyOf(destination) : null
  const isCard = destination?.kind === 'card'

  return (
    <form className={styles.form} onSubmit={submit} aria-busy={sending} noValidate>
      <fieldset className={styles.fieldset} aria-describedby={`${legendId}-hint`}>
        <legend id={legendId} className={styles.legend}>
          Destino
        </legend>
        <p id={`${legendId}-hint`} className={styles.hint}>
          {isCard
            ? 'As compras e os créditos da fatura entram neste cartão. Pagamentos da fatura não são importados aqui: eles vêm do extrato da conta.'
            : 'Extrato de conta entra numa conta; fatura de cartão, num cartão.'}
        </p>
        {accounts.length > 0 ? (
          <div className={styles.destinationGroup} role="group" aria-label="Contas">
            <p className={styles.groupTitle} aria-hidden="true">
              Contas
            </p>
            <div className={styles.accounts}>
              {accounts.map((account) => {
                const Icon = ACCOUNT_ICON[account.type] ?? Wallet
                return (
                  <Option
                    key={account.id}
                    checked={selected === `account:${account.id}`}
                    onChange={() => choose({ kind: 'account', id: account.id })}
                    icon={<Icon size={20} strokeWidth={1.75} />}
                    name={account.name}
                    meta={[account.institutionName, ACCOUNT_TYPE[account.type] ?? 'Conta'].filter(Boolean).join(' · ')}
                  />
                )
              })}
            </div>
          </div>
        ) : null}
        {cards.length > 0 ? (
          <div className={styles.destinationGroup} role="group" aria-label="Cartões">
            <p className={styles.groupTitle} aria-hidden="true">
              Cartões
            </p>
            <div className={styles.accounts}>
              {cards.map((card) => (
                <Option
                  key={card.id}
                  checked={selected === `card:${card.id}`}
                  onChange={() => choose({ kind: 'card', id: card.id })}
                  icon={<CreditCard size={20} strokeWidth={1.75} />}
                  name={card.nickname}
                  meta={`Cartão •••• ${card.last4} · fatura`}
                />
              ))}
            </div>
          </div>
        ) : null}
      </fieldset>

      {isCard ? (
        <p className={styles.info} role="note">
          No CSV da fatura, valores positivos são compras e negativos são créditos. Parcelas como “3/10” são
          reconhecidas pela descrição ou pela coluna <code>parcela</code>.{' '}
          <a href="/modelo-fatura.csv" download className={styles.link}>
            Baixar modelo de fatura
          </a>
        </p>
      ) : null}

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
        <Button type="submit" disabled={!destination || !file || sending}>
          {sending ? 'Lendo o arquivo…' : 'Continuar'}
        </Button>
      </div>
    </form>
  )
}

function Option({
  checked,
  onChange,
  icon,
  name,
  meta,
}: {
  checked: boolean
  onChange: () => void
  icon: React.ReactNode
  name: string
  meta: string
}) {
  return (
    <label className={styles.account}>
      <input type="radio" name="destination" checked={checked} onChange={onChange} className={styles.radio} />
      <span className={styles.accountIcon} aria-hidden="true">
        {icon}
      </span>
      <span className={styles.accountText}>
        <span className={styles.accountName}>{name}</span>
        <span className={styles.accountMeta}>{meta}</span>
      </span>
    </label>
  )
}
