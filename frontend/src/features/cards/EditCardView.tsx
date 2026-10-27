'use client'

import { Archive, CircleAlert, RotateCcw } from 'lucide-react'
import Link from 'next/link'
import { useState } from 'react'
import { Button } from '@/components/ui/Button'
import { Money } from '@/components/ui/Money'
import { useAccounts } from '@/features/transactions/hooks'
import { archiveCard, reactivateCard, updateCard, type Card, type NewCard } from './api'
import { CardForm, type CardFormValues } from './CardForm'
import { cardLabel, cycleText } from './format'
import { useCard } from './hooks'
import { cardReadFailureMessage } from './messages'
import { Badge } from './parts'
import { BackLink, ErrorPanel, LoadingPanel } from './states'
import { CardNotFound } from './CardDetailView'
import styles from './Cards.module.css'

/** Valores do formulário a partir do cartão salvo; o limite vira texto pt-BR sem passar por `number`. */
function toFormValues(card: Card): CardFormValues {
  return {
    nickname: card.nickname,
    institutionName: card.institutionName ?? '',
    brand: card.brand,
    last4: card.last4,
    limit: card.limit.amount.replace('.', ','),
    closingDay: String(card.closingDay),
    dueDay: String(card.dueDay),
    defaultPaymentAccountId: card.defaultPaymentAccountId ?? '',
  }
}

/**
 * Edição, arquivamento e reativação do cartão (ADR-0026 §2; ADR-0029 §7). Mudar fechamento ou vencimento recalcula só as
 * faturas ainda não fechadas, no backend. Arquivar pede confirmação; o histórico fica guardado.
 */
export function EditCardView({ id }: { id: string | null }) {
  if (!id) return <CardNotFound />
  return <EditCard id={id} />
}

function EditCard({ id }: { id: string }) {
  const card = useCard(id)
  if (card.status === 'loading') {
    return (
      <div className={styles.page}>
        <BackLink href={`/cards/${id}`}>Cartão</BackLink>
        <LoadingPanel label="Carregando o cartão…" rows={5} />
      </div>
    )
  }
  if (card.status === 'error' || !card.data) {
    if (card.status === 'error' && card.failure.code === 'CARD_NOT_FOUND') return <CardNotFound />
    return (
      <div className={styles.page}>
        <BackLink href={`/cards/${id}`}>Cartão</BackLink>
        <ErrorPanel
          title="Não foi possível carregar o cartão"
          code={card.status === 'error' ? card.failure.code : 'UNAVAILABLE'}
          onRetry={card.retry}
        />
      </div>
    )
  }
  return <Loaded key={card.attempt} card={card.data} onChange={card.replace} onReload={card.retry} />
}

function Loaded({ card, onChange, onReload }: { card: Card; onChange: (card: Card) => void; onReload: () => void }) {
  const accounts = useAccounts()
  const [notice, setNotice] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)
  const archived = card.status === 'ARCHIVED'

  async function save(fields: NewCard) {
    setNotice(null)
    setActionError(null)
    const result = await updateCard(card.id, fields)
    if (!result.ok) {
      if (result.code === 'CONFLICT' || result.code === 'CARD_ARCHIVED') {
        setActionError(result.code)
        return null
      }
      if (result.code === 'CARD_NOT_FOUND') {
        onReload()
        return null
      }
      return result
    }
    onChange(result.data)
    setNotice('Alterações salvas. Faturas já fechadas não mudam; as abertas seguem o novo ciclo.')
    return null
  }

  async function changeStatus(action: 'archive' | 'reactivate') {
    if (busy) return
    setBusy(true)
    setNotice(null)
    setActionError(null)
    const result = action === 'archive' ? await archiveCard(card.id) : await reactivateCard(card.id)
    setBusy(false)
    setConfirming(false)
    if (!result.ok) {
      if (result.code === 'CARD_NOT_FOUND') return onReload()
      return setActionError(result.code)
    }
    onChange(result.data)
    setNotice(
      action === 'archive'
        ? 'Cartão arquivado. O histórico foi mantido e você pode reativá-lo quando quiser.'
        : 'Cartão reativado. Ele volta a receber compras e pagamentos.',
    )
  }

  return (
    <div className={styles.page}>
      <nav aria-label="Você está em" className={styles.breadcrumb}>
        <Link href="/cards">Cartões</Link>
        <span aria-hidden="true">›</span>
        <Link href={`/cards/${card.id}`}>{card.nickname}</Link>
        <span aria-hidden="true">›</span>
        <span aria-current="page">{archived ? 'Dados do cartão' : 'Editar'}</span>
      </nav>
      <header className={styles.header}>
        <div className={styles.headerText}>
          <h1 className={styles.title}>{archived ? 'Dados do cartão' : 'Editar cartão'}</h1>
          <p className={styles.subtitle}>
            {cardLabel(card)} · {cycleText(card)}
          </p>
          {archived ? <Badge tone="neutral">Arquivado</Badge> : null}
        </div>
      </header>

      <div className={styles.live} role="status">
        {notice}
      </div>
      {actionError ? (
        <div className={styles.error} role="alert">
          <CircleAlert size={18} strokeWidth={1.75} aria-hidden="true" />
          <span>{cardReadFailureMessage(actionError)}</span>
          {actionError === 'CONFLICT' || actionError === 'CARD_ARCHIVED' ? (
            <Button variant="secondary" onClick={onReload}>
              Recarregar dados
            </Button>
          ) : null}
        </div>
      ) : null}

      {archived ? (
        <section className={styles.panel} aria-labelledby="archived-title">
          <h2 id="archived-title" className={styles.sectionTitle}>
            Cartão arquivado
          </h2>
          <p className={styles.muted}>
            O histórico continua guardado, mas o cartão não recebe compras nem pagamentos e não pode ser editado. Limite:{' '}
            <Money value={card.limit} />. Reative para voltar a usá-lo.
          </p>
          <div>
            <Button onClick={() => changeStatus('reactivate')} disabled={busy}>
              <RotateCcw size={18} strokeWidth={1.75} aria-hidden="true" />
              {busy ? 'Reativando…' : 'Reativar cartão'}
            </Button>
          </div>
        </section>
      ) : (
        <>
          <section className={styles.panel} aria-labelledby="edit-card-title">
            <h2 id="edit-card-title" className={styles.sectionTitle}>
              Dados do cartão
            </h2>
            {accounts.status === 'loading' ? (
              <LoadingPanel label="Carregando suas contas…" rows={2} />
            ) : (
              <CardForm
                accounts={accounts.status === 'success' ? accounts.data.filter((account) => account.status === 'ACTIVE') : []}
                initial={toFormValues(card)}
                submitLabel="Salvar alterações"
                submittingLabel="Salvando…"
                cancelHref={`/cards/${card.id}`}
                onSubmit={save}
              />
            )}
          </section>

          <section className={styles.panel} aria-labelledby="archive-title">
            <h2 id="archive-title" className={styles.sectionTitle}>
              Arquivar cartão
            </h2>
            <p className={styles.muted}>
              O cartão sai das listas e da utilização e não recebe compras nem pagamentos novos. Faturas, parcelamentos e
              lançamentos continuam guardados, e você pode reativá-lo depois.
            </p>
            {confirming ? (
              <div className={styles.confirm} role="group" aria-labelledby="archive-confirm">
                <span id="archive-confirm">Arquivar o {card.nickname}? Compras e pagamentos novos ficam bloqueados.</span>
                <div className={styles.actions}>
                  <Button onClick={() => changeStatus('archive')} disabled={busy}>
                    <Archive size={18} strokeWidth={1.75} aria-hidden="true" />
                    {busy ? 'Arquivando…' : 'Confirmar arquivamento'}
                  </Button>
                  <Button variant="secondary" onClick={() => setConfirming(false)} disabled={busy}>
                    Cancelar
                  </Button>
                </div>
              </div>
            ) : (
              <div>
                <Button variant="secondary" onClick={() => setConfirming(true)}>
                  <Archive size={18} strokeWidth={1.75} aria-hidden="true" />
                  Arquivar cartão
                </Button>
              </div>
            )}
          </section>
        </>
      )}
    </div>
  )
}
