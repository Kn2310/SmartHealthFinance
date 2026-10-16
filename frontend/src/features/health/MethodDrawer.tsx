'use client'

import { X } from 'lucide-react'
import { useCallback, useEffect, useId, useRef, useState, type KeyboardEvent, type ReactNode, type RefObject } from 'react'
import type { HealthMethod } from '@/lib/api/types'
import { useHealthMethod } from './api'
import { CONFIDENCE_LABEL, curvePoint } from './format'
import styles from './Health.module.css'

const FOCUSABLE = 'a[href], button:not([disabled]), summary, [tabindex]:not([tabindex="-1"])'

/**
 * Gatilho + drawer "Como calculamos?" (D-HealthMethod, 520 px; bottom sheet no mobile). Diálogo modal nativo: foco
 * preso dentro dele, Esc fecha e o foco volta ao gatilho. O conteúdo vem de `/health/method` (as mesmas constantes do
 * cálculo, ADR-0019 §4) e só é carregado ao abrir.
 */
export function MethodTrigger({ className, children = 'Como calculamos?' }: { className?: string; children?: ReactNode }) {
  const [open, setOpen] = useState(false)
  // Montado na primeira abertura e mantido: abrir e fechar só chamam showModal()/close() no diálogo nativo, que
  // devolve o foco a quem abriu (desmontar um modal aberto deixaria o foco no body).
  const [mounted, setMounted] = useState(false)
  const trigger = useRef<HTMLButtonElement>(null)
  const close = useCallback(() => setOpen(false), [])
  return (
    <>
      <button
        ref={trigger}
        type="button"
        className={className}
        aria-haspopup="dialog"
        onClick={() => {
          setMounted(true)
          setOpen(true)
        }}
      >
        {children}
      </button>
      {mounted ? <MethodDrawer open={open} onClose={close} trigger={trigger} /> : null}
    </>
  )
}

function MethodDrawer({ open, onClose, trigger }: { open: boolean; onClose: () => void; trigger: RefObject<HTMLButtonElement | null> }) {
  const id = useId()
  const dialog = useRef<HTMLDialogElement>(null)
  const method = useHealthMethod(open)

  const wasOpen = useRef(false)
  useEffect(() => {
    const element = dialog.current
    if (!element) return
    if (open) {
      wasOpen.current = true
      if (!element.open) {
        if (typeof element.showModal === 'function') element.showModal()
        else element.setAttribute('open', '')
      }
      element.querySelector<HTMLElement>('[data-autofocus]')?.focus()
      return
    }
    if (element.open) {
      if (typeof element.close === 'function') element.close()
      else element.removeAttribute('open')
    }
    // O Chrome pode fechar o modal sozinho no Esc (close watcher) antes deste efeito: o foco volta de qualquer jeito.
    if (wasOpen.current) {
      wasOpen.current = false
      trigger.current?.focus()
    }
  }, [open, trigger])

  const onKeyDown = (event: KeyboardEvent<HTMLDialogElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault()
      onClose()
      return
    }
    if (event.key !== 'Tab') return
    // O modal nativo já torna o resto inerte; o laço explícito garante o foco preso também onde ele não existe.
    const items = [...(dialog.current?.querySelectorAll<HTMLElement>(FOCUSABLE) ?? [])]
    if (items.length === 0) return
    const first = items[0]!
    const last = items.at(-1)!
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first.focus()
    }
  }

  return (
    <dialog
      ref={dialog}
      className={styles.drawer}
      aria-labelledby={`${id}-title`}
      aria-modal="true"
      onKeyDown={onKeyDown}
      // Fechado por outro caminho do navegador: o estado acompanha.
      onClose={onClose}
      onCancel={(event) => {
        event.preventDefault()
        onClose()
      }}
      onClick={(event) => {
        // Clique no fundo (fora do painel) fecha.
        if (event.target === dialog.current) onClose()
      }}
    >
      <div className={styles.drawerPanel}>
        <header className={styles.drawerHeader}>
          <h2 id={`${id}-title`} className={styles.drawerTitle}>
            Como calculamos?
          </h2>
          <button type="button" className={styles.iconButton} onClick={onClose} aria-label="Fechar" data-autofocus>
            <X size={20} aria-hidden="true" />
          </button>
        </header>
        <div className={styles.drawerBody}>
          {!open ? null : method.status === 'loading' ? (
            <p role="status">Carregando o método…</p>
          ) : method.status === 'error' ? (
            <div role="alert" className={styles.inlineError}>
              <p>Não conseguimos carregar o método agora. Seus dados estão seguros.</p>
              <button type="button" className={styles.linkButton} onClick={method.retry}>
                Tentar novamente
              </button>
            </div>
          ) : method.data ? (
            <MethodContent method={method.data} />
          ) : null}
        </div>
      </div>
    </dialog>
  )
}

function MethodContent({ method }: { method: HealthMethod }) {
  return (
    <>
      <p>{method.about}</p>

      <section aria-labelledby="method-dimensions" className={styles.drawerSection}>
        <h3 id="method-dimensions" className={styles.drawerSubtitle}>
          As seis dimensões
        </h3>
        <ul className={styles.methodList}>
          {method.dimensions.map((d) => (
            <li key={d.dimension} className={styles.methodItem}>
              <div className={styles.methodHead}>
                <strong>{d.label}</strong>
                <span className={styles.methodWeight}>{d.applicable ? `peso ${d.weight}` : 'Em breve'}</span>
              </div>
              <p>{d.observes}</p>
              {d.applicable ? (
                <>
                  <p className={styles.methodNote}>{d.formula}</p>
                  <p className={styles.methodNote}>Mínimo: {d.minimum}</p>
                  {d.curves.map((curve) => (
                    <p key={curve.name} className={styles.methodNote}>
                      {curve.name} ({curve.input}):{' '}
                      {curve.points.map((p) => `${curvePoint(d.dimension, p.x)} → ${p.score}`).join(' · ')}
                    </p>
                  ))}
                </>
              ) : (
                <p className={styles.methodNote}>{d.formula}</p>
              )}
            </li>
          ))}
        </ul>
      </section>

      <section aria-labelledby="method-overall" className={styles.drawerSection}>
        <h3 id="method-overall" className={styles.drawerSubtitle}>
          Como as dimensões viram um score
        </h3>
        <p>{method.overall}</p>
        <p className={styles.methodNote}>{method.rounding}</p>
      </section>

      <section aria-labelledby="method-period" className={styles.drawerSection}>
        <h3 id="method-period" className={styles.drawerSubtitle}>
          Período e fontes
        </h3>
        <p>{method.window}</p>
        <p>{method.partialMonth}</p>
        <p>Fontes: {method.sources.join(', ')}.</p>
      </section>

      <section aria-labelledby="method-precision" className={styles.drawerSection}>
        <h3 id="method-precision" className={styles.drawerSubtitle}>
          Precisão
        </h3>
        <p>{method.estimated}</p>
        <ul className={styles.plainList}>
          {method.confidence.map((rule) => (
            <li key={rule.level}>
              Confiança {CONFIDENCE_LABEL[rule.level]}: {rule.rule}
            </li>
          ))}
        </ul>
      </section>

      <section aria-labelledby="method-out" className={styles.drawerSection}>
        <h3 id="method-out" className={styles.drawerSubtitle}>
          O que não entra
        </h3>
        <ul className={styles.plainList}>
          {method.exclusions.map((item) => (
            <li key={item}>{item}</li>
          ))}
        </ul>
        <p className={styles.methodNote}>Versão do cálculo: {method.algorithmVersion}</p>
      </section>
    </>
  )
}
