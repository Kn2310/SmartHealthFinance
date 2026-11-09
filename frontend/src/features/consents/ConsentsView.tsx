'use client'

import { Info } from 'lucide-react'
import { useEffect, useId, useRef, useState } from 'react'
import { Button } from '@/components/ui/Button'
import { Skeleton } from '@/components/ui/Skeleton'
import type { ConsentPurpose } from '@/lib/api/schema'
import { changeConsent, type Consent } from './api'
import { useConsents } from './hooks'
import { CONSENT_COPY, changeErrorMessage, formatConsentDate, loadErrorMessage } from './messages'
import styles from './Consents.module.css'

/** Revogações que apagam o histórico pedem confirmação (ADR-0038 §4, §9). */
type Pending = { purpose: ConsentPurpose; erasesHistory: true }

const isActive = (consent: Consent | undefined) => consent?.state === 'ACTIVE'

function metaLine(consent: Consent, items: Consent[]): string {
  const purpose = CONSENT_COPY[consent.purpose].purpose
  const missing = consent.requires.some((required) => !isActive(items.find((c) => c.purpose === required)))
  switch (consent.state) {
    case 'ACTIVE':
      return `Concedido em ${consent.grantedAt ? formatConsentDate(consent.grantedAt) : ''} · ${purpose}`
    case 'REVOKED':
      return missing
        ? 'Revogado · exige permitir que o Copilot use seus dados'
        : `Revogado em ${consent.revokedAt ? formatConsentDate(consent.revokedAt) : ''} · ${purpose}`
    case 'EXPIRED':
      return 'Os termos mudaram · conceda de novo para continuar'
    default:
      return missing ? 'Não concedido · exige permitir que o Copilot use seus dados' : `Não concedido · ${purpose}`
  }
}

/** D-ProfileConsents: switches com a data de concessão e o alerta de revogação. Só os consentimentos do Copilot. */
export function ConsentsView() {
  const consents = useConsents()
  const [busy, setBusy] = useState<ConsentPurpose | null>(null)
  const [pending, setPending] = useState<Pending | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState('')
  const titleId = useId()
  const confirmRef = useRef<HTMLDivElement>(null)
  const switches = useRef(new Map<ConsentPurpose, HTMLButtonElement | null>())

  // A confirmação recebe o foco ao abrir, para o leitor de tela anunciar a pergunta.
  useEffect(() => {
    if (pending) confirmRef.current?.focus()
  }, [pending])

  function cancel() {
    const purpose = pending?.purpose
    setPending(null)
    if (purpose) requestAnimationFrame(() => switches.current.get(purpose)?.focus())
  }

  async function apply(purpose: ConsentPurpose, granted: boolean) {
    setBusy(purpose)
    setError(null)
    setNotice('')
    const result = await changeConsent(purpose, granted)
    setBusy(null)
    setPending(null)
    if (result.ok) {
      consents.replace(result.data.items)
      setNotice(granted ? 'Consentimento concedido.' : 'Consentimento revogado.')
    } else {
      setError(changeErrorMessage(result.code))
    }
  }

  function toggle(consent: Consent, items: Consent[]) {
    if (busy) return
    if (consent.state !== 'ACTIVE') {
      void apply(consent.purpose, true)
      return
    }
    // A cascata também revoga um histórico expirado (termo antigo): ele ainda guarda conversas (ADR-0038 §3–§4).
    const history = items.find((c) => c.purpose === 'COPILOT_HISTORY')
    const holdsHistory = history?.state === 'ACTIVE' || history?.state === 'EXPIRED'
    const erasesHistory = consent.purpose === 'COPILOT_HISTORY' || holdsHistory
    if (erasesHistory) setPending({ purpose: consent.purpose, erasesHistory: true })
    else void apply(consent.purpose, false)
  }

  return (
    <section className={styles.content} aria-labelledby={titleId}>
      <div>
        <h2 id={titleId} className={styles.title}>
          Consentimentos
        </h2>
        <p className={styles.subtitle}>Você decide como seus dados são usados.</p>
      </div>

      {consents.status === 'loading' ? (
        <div role="status" aria-busy="true" className={styles.card}>
          <span className="sr-only">Carregando…</span>
          <Skeleton height="row" />
          <Skeleton height="row" />
        </div>
      ) : consents.status === 'error' ? (
        <section role="alert" aria-labelledby="consents-error-title" className={styles.card}>
          <h3 id="consents-error-title" className={styles.label}>
            Não foi possível carregar
          </h3>
          <p className={styles.meta}>{loadErrorMessage(consents.failure.code)}</p>
          <div>
            <Button onClick={consents.retry}>Tentar novamente</Button>
          </div>
        </section>
      ) : (
        <div className={styles.card}>
          <ul className={styles.list}>
            {consents.data.map((consent) => {
              const copy = CONSENT_COPY[consent.purpose]
              const labelId = `consent-${consent.purpose}-label`
              const metaId = `consent-${consent.purpose}-meta`
              const blocked =
                consent.state !== 'ACTIVE' &&
                consent.requires.some((required) => !isActive(consents.data.find((c) => c.purpose === required)))
              return (
                <li key={consent.purpose} className={styles.row}>
                  <div className={styles.text}>
                    <span id={labelId} className={styles.label}>
                      {copy.label}
                    </span>
                    <span id={metaId} className={styles.meta}>
                      {metaLine(consent, consents.data)}
                    </span>
                  </div>
                  <button
                    ref={(element) => {
                      switches.current.set(consent.purpose, element)
                    }}
                    type="button"
                    role="switch"
                    aria-checked={consent.state === 'ACTIVE'}
                    aria-labelledby={labelId}
                    aria-describedby={metaId}
                    className={styles.switch}
                    disabled={blocked || busy !== null || pending !== null}
                    onClick={() => toggle(consent, consents.data)}
                  />
                </li>
              )
            })}
          </ul>

          {pending ? (
            <div
              ref={confirmRef}
              tabIndex={-1}
              className={styles.confirm}
              role="alertdialog"
              aria-labelledby="consent-confirm-title"
            >
              <p id="consent-confirm-title">
                <strong>
                  {pending.purpose === 'COPILOT_HISTORY' ? 'Apagar o histórico do Copilot?' : 'Revogar e apagar o histórico?'}
                </strong>{' '}
                {pending.purpose === 'COPILOT_HISTORY'
                  ? 'As conversas guardadas serão apagadas de vez. Isso não pode ser desfeito.'
                  : 'O Copilot deixa de usar seus dados e as conversas guardadas serão apagadas de vez. Isso não pode ser desfeito.'}
              </p>
              <div className={styles.confirmActions}>
                <Button onClick={() => void apply(pending.purpose, false)} disabled={busy !== null}>
                  {busy ? 'Salvando…' : 'Revogar'}
                </Button>
                <Button variant="secondary" onClick={cancel} disabled={busy !== null}>
                  Cancelar
                </Button>
              </div>
            </div>
          ) : null}

          {error ? (
            <p role="alert" className={styles.error}>
              {error}
            </p>
          ) : null}
        </div>
      )}

      <p className="sr-only" role="status" aria-live="polite">
        {notice}
      </p>

      <div role="note" className={styles.note}>
        <Info size={18} strokeWidth={1.75} aria-hidden="true" className={styles.noteIcon} />
        <div className={styles.noteText}>
          <strong>Você pode revogar a qualquer momento</strong>
          <span>
            Ao revogar, os recursos que dependem do consentimento ficam indisponíveis até você conceder novamente. Seus
            dados importados não são apagados.
          </span>
        </div>
      </div>
    </section>
  )
}
