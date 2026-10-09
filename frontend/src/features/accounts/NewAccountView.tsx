'use client'

import { ChevronLeft, CircleAlert, PlugZap, TriangleAlert } from 'lucide-react'
import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { useId, useRef, useState } from 'react'
import { Button } from '@/components/ui/Button'
import { parseAmountInput } from '@/lib/format/amount-input'
import { fieldErrorMessage, requestFailureMessage as transactionFailureMessage } from '@/features/transactions/messages'
import { AccountForm } from './AccountForm'
import { createAccount, retryOpeningBalance, type AccountFields, type OpeningBalance } from './api'
import { cancelDestination, destinationAfterCreate, type ReturnTo } from './return-to'
import styles from './Accounts.module.css'
import txnStyles from '@/features/transactions/Transactions.module.css'

const EMPTY: AccountFields = { name: '', type: 'CHECKING', institutionName: null, includedInTotal: true }

type Pending = { accountId: string; opening: OpeningBalance }

/**
 * "Criar conta manual" de D-AddAccount, como página. A conta nasce sem saldo (ADR-0004 §4); o saldo inicial
 * opcional vira um ajuste (`ADJUSTMENT`) lançado pelo BFF logo depois da criação. Não é atômico: se o ajuste
 * falhar, a conta já existe e a tela oferece tentar de novo (mesma chave no BFF — nunca duplica).
 */
export function NewAccountView({ returnTo }: { returnTo: ReturnTo | null }) {
  const router = useRouter()
  const id = useId()
  const back = cancelDestination(returnTo)
  const [amount, setAmount] = useState('')
  const [direction, setDirection] = useState<OpeningBalance['direction']>('INCREASE')
  const [amountError, setAmountError] = useState<string | null>(null)
  const [pending, setPending] = useState<Pending | null>(null)
  const amountInput = useRef<HTMLInputElement>(null)

  async function submit(fields: AccountFields) {
    let opening: OpeningBalance | undefined
    if (amount.trim() !== '') {
      const parsed = parseAmountInput(amount)
      if (!parsed.ok) {
        setAmountError(fieldErrorMessage('amount', parsed.error))
        amountInput.current?.focus()
        return null
      }
      opening = { amount: parsed.value, direction }
    }
    const result = await createAccount(fields, opening)
    if (!result.ok) {
      const detail = result.details?.find((issue) => issue.field.startsWith('openingBalance.'))
      if (detail) {
        setAmountError(fieldErrorMessage('amount', detail.code))
        amountInput.current?.focus()
        return null
      }
      return result
    }
    if (opening && result.data.openingBalance === 'FAILED') {
      setPending({ accountId: result.data.id, opening })
      return 'leaving' as const
    }
    router.push(destinationAfterCreate(returnTo, result.data.id))
    return 'leaving' as const
  }

  if (pending) {
    return (
      <OpeningBalanceRetry pending={pending} onDone={() => router.push(destinationAfterCreate(returnTo, pending.accountId))} />
    )
  }

  return (
    <div className={styles.narrow}>
      <Link href={back} className={styles.back}>
        <ChevronLeft size={18} strokeWidth={1.75} aria-hidden="true" />
        {returnTo === 'import' ? 'Importar extrato' : 'Contas'}
      </Link>
      <header className={styles.pageHeader}>
        <h1 className={styles.pageTitle}>Adicionar conta</h1>
        <p className={styles.pageSubtitle}>
          {returnTo === 'import'
            ? 'Crie a conta que vai receber as movimentações. Em seguida, você volta para a importação com ela selecionada.'
            : 'Crie uma conta manual. O saldo vem das movimentações que você importar ou registrar.'}
        </p>
      </header>

      <section className={styles.panel} aria-labelledby="new-account-title">
        <h2 id="new-account-title" className={styles.sectionTitle}>
          Dados da conta
        </h2>
        <AccountForm
          initial={EMPTY}
          submitLabel={returnTo === 'import' ? 'Criar e continuar' : 'Criar conta'}
          submittingLabel="Criando conta…"
          onSubmit={submit}
          cancelHref={back}
        >
          <fieldset className={styles.fields}>
            <legend className={styles.sectionTitle}>
              Saldo inicial <span className={styles.optional}>(opcional)</span>
            </legend>
            <div className={styles.field}>
              <label htmlFor={`${id}-opening`} className={styles.label}>
                Saldo de hoje
              </label>
              <div className={txnStyles.money}>
                <span className={txnStyles.currency} aria-hidden="true">
                  R$
                </span>
                <input
                  ref={amountInput}
                  id={`${id}-opening`}
                  name="openingBalance"
                  className={`${styles.input} ${txnStyles.moneyInput}`}
                  inputMode="decimal"
                  autoComplete="off"
                  placeholder="0,00"
                  value={amount}
                  onChange={(event) => {
                    setAmount(event.target.value)
                    setAmountError(null)
                  }}
                  aria-invalid={amountError ? true : undefined}
                  aria-describedby={[`${id}-opening-hint`, amountError ? `${id}-opening-error` : null].filter(Boolean).join(' ')}
                />
              </div>
              <p id={`${id}-opening-hint`} className={styles.fieldHint}>
                O saldo que a conta tem hoje no banco. Ele entra como um ajuste de saldo (não conta como receita) e
                pode ser corrigido depois com outro ajuste. Deixe em branco para começar sem saldo.
              </p>
              {amountError ? (
                <p id={`${id}-opening-error`} className={styles.fieldError}>
                  <CircleAlert size={16} strokeWidth={1.75} aria-hidden="true" />
                  <span>{amountError}</span>
                </p>
              ) : null}
            </div>
            <fieldset className={txnStyles.fieldset}>
              <legend className={txnStyles.legend}>Este saldo é</legend>
              <div className={`${txnStyles.segments} ${txnStyles.two}`}>
                {(
                  [
                    ['INCREASE', 'Positivo'],
                    ['DECREASE', 'Negativo'],
                  ] as const
                ).map(([value, label]) => (
                  <label key={value} className={txnStyles.segment}>
                    <input
                      type="radio"
                      name="openingDirection"
                      value={value}
                      className={txnStyles.segmentInput}
                      checked={direction === value}
                      onChange={() => setDirection(value)}
                    />
                    {label}
                  </label>
                ))}
              </div>
            </fieldset>
          </fieldset>

          <div className={styles.unavailable}>
            <span className={styles.unavailableIcon} aria-hidden="true">
              <PlugZap size={20} strokeWidth={1.75} />
            </span>
            <p>
              <span className={styles.soonBadge}>Em breve</span> Conexão automática com bancos (Open Finance) estará
              disponível em uma versão futura.
            </p>
          </div>
        </AccountForm>
      </section>
    </div>
  )
}

/** Conta criada, saldo inicial não lançado: explica, oferece tentar de novo e permite seguir sem ele. */
function OpeningBalanceRetry({ pending, onDone }: { pending: Pending; onDone: () => void }) {
  const [state, setState] = useState<{ status: 'idle' | 'sending' } | { status: 'error'; code: string }>({ status: 'idle' })

  async function retry() {
    if (state.status === 'sending') return
    setState({ status: 'sending' })
    const result = await retryOpeningBalance(pending.accountId, pending.opening)
    if (result.ok) return onDone()
    setState({ status: 'error', code: result.code })
  }

  const exists = state.status === 'error' && state.code === 'OPENING_BALANCE_EXISTS'

  return (
    <div className={styles.narrow}>
      <section className={`${styles.panel} ${styles.danger}`} aria-labelledby="opening-failed-title">
        <span className={styles.stateIcon} aria-hidden="true">
          <TriangleAlert size={24} strokeWidth={1.75} />
        </span>
        <h1 id="opening-failed-title" className={styles.sectionTitle}>
          Conta criada, mas o saldo inicial não foi lançado
        </h1>
        <p className={styles.muted}>
          A conta já existe e está sem saldo. Tente lançar o saldo inicial de novo — se o primeiro envio tiver chegado,
          ele não será duplicado.
        </p>
        {state.status === 'error' ? (
          <p className={styles.error} role="alert">
            <CircleAlert size={18} strokeWidth={1.75} aria-hidden="true" />
            <span>{transactionFailureMessage(state.code)}</span>
          </p>
        ) : null}
        <div className={styles.emptyActions}>
          {exists ? null : (
            <Button onClick={retry} disabled={state.status === 'sending'}>
              {state.status === 'sending' ? 'Lançando…' : 'Tentar lançar saldo inicial novamente'}
            </Button>
          )}
          <Button variant={exists ? 'primary' : 'secondary'} onClick={onDone} disabled={state.status === 'sending'}>
            {exists ? 'Continuar' : 'Continuar sem saldo inicial'}
          </Button>
        </div>
      </section>
    </div>
  )
}
