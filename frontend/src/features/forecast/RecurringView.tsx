'use client'

import Link from 'next/link'
import { useSearchParams } from 'next/navigation'
import { useEffect, useRef, useState } from 'react'
import { Button } from '@/components/ui/Button'
import { ProjectedValue } from '@/components/ui/ProjectedValue'
import { Skeleton } from '@/components/ui/Skeleton'
import type { ForecastRecurringSeries, ForecastSeriesStatus } from '@/lib/api/types'
import { confidenceText, NOTE_RECALCULATING, seriesValue } from './format'
import { RecurringDrawer } from './RecurringDrawer'
import {
  confirmSeries,
  fetchRecurring,
  forecastSettled,
  ignoreSeries,
  useChannelNames,
  useRecurringList,
  type RecurringKind,
} from './recurring'
import styles from './Recurring.module.css'

/** O que uma ação devolve à tela: a série atualizada e a mensagem do aviso. */
export interface ActionOutcome {
  series: ForecastRecurringSeries
  message: string
  /** Série que "Desfazer" confirma de volta (só ao ignorar). */
  undo?: string
}

const TABS: { kind: RecurringKind; tab: string; label: string }[] = [
  { kind: 'expenses', tab: 'expenses', label: 'Despesas' },
  { kind: 'incomes', tab: 'incomes', label: 'Receitas' },
]

const POLL_MS = 2000
const POLL_MAX = 15

const SECTIONS: { status: ForecastSeriesStatus; title: string; hint: string }[] = [
  { status: 'DETECTED', title: 'Para revisar', hint: 'Encontramos estas recorrências no seu histórico. Confirme as que fazem sentido.' },
  { status: 'CONFIRMED', title: 'Confirmadas', hint: 'Entram nas estimativas e nos próximos eventos.' },
  { status: 'IGNORED', title: 'Ignoradas', hint: 'Não entram nas estimativas.' },
]

/**
 * Recorrências (`/recurring`, ADR-0033 §5): despesas e receitas recorrentes detectadas pelo Forecast, com confirmar,
 * ignorar (desfazer) e ajustar. Só exibe e envia as ações: nenhum valor é calculado aqui (ADR-0032 §2).
 */
export function RecurringView() {
  const searchParams = useSearchParams()
  const kind: RecurringKind = searchParams.get('tab') === 'incomes' ? 'incomes' : 'expenses'
  const list = useRecurringList(kind)
  const names = useChannelNames()

  const [notice, setNotice] = useState<{ message: string; undo?: string; kind: RecurringKind } | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [adjusting, setAdjusting] = useState<{ series: ForecastRecurringSeries; trigger: HTMLElement | null } | null>(null)
  const [recalcRun, setRecalcRun] = useState(0)

  // Depois de uma ação o recálculo é assíncrono: pergunta o status até terminar e então relê a lista, sem piscar.
  const { replace } = list
  useEffect(() => {
    if (recalcRun === 0) return
    let cancelled = false
    let timer: ReturnType<typeof setTimeout>
    const tick = (attempt: number) => {
      timer = setTimeout(async () => {
        const done = (await forecastSettled()) || attempt >= POLL_MAX
        if (cancelled) return
        if (!done) return tick(attempt + 1)
        const fresh = await fetchRecurring(kind)
        if (cancelled) return
        if (fresh.ok) replace(fresh.data)
        setRecalcRun(0)
      }, POLL_MS)
    }
    tick(0)
    return () => {
      cancelled = true
      clearTimeout(timer)
    }
  }, [recalcRun, kind, replace])

  function apply(outcome: ActionOutcome) {
    if (list.status === 'success') {
      replace({ items: list.data.items.map((item) => (item.id === outcome.series.id ? outcome.series : item)) })
    }
    setNotice({ message: outcome.message, undo: outcome.undo, kind })
    setActionError(null)
    setRecalcRun((n) => n + 1)
  }

  async function run(series: ForecastRecurringSeries, action: 'confirm' | 'ignore') {
    setBusy(series.id)
    setActionError(null)
    const result = await (action === 'confirm' ? confirmSeries : ignoreSeries)(kind, series.id)
    setBusy(null)
    if (!result.ok) {
      setActionError(result.code === 'RECURRING_SERIES_NOT_FOUND'
        ? 'Esta recorrência não existe mais. Atualize a página.'
        : 'Não conseguimos concluir agora. Seus dados estão seguros. Tente de novo.')
      return
    }
    apply({
      series: result.data.series,
      message: action === 'confirm' ? 'Recorrência confirmada.' : 'Recorrência ignorada.',
      undo: action === 'ignore' ? series.id : undefined,
    })
  }

  async function undo(id: string, seriesKind: RecurringKind) {
    setBusy(id)
    const result = await confirmSeries(seriesKind, id)
    setBusy(null)
    if (!result.ok) {
      setActionError('Não conseguimos desfazer agora. Use "Confirmar" na lista de ignoradas.')
      return
    }
    apply({ series: result.data.series, message: 'Recorrência restaurada e confirmada.' })
  }

  const items = list.status === 'success' ? list.data.items : []

  return (
    <div className={styles.page}>
      <header>
        <h1 className={styles.pageTitle}>Recorrências</h1>
        <p className={styles.pageSubtitle}>
          Gastos e receitas que se repetem todo mês. Elas alimentam as estimativas da Home, da Análise e dos Cartões.
        </p>
      </header>

      <nav aria-label="Tipo de recorrência">
        <div className={styles.tabs}>
          {TABS.map((item) => (
            <Link key={item.tab} href={`/recurring?tab=${item.tab}`} className={styles.tab}
              aria-current={item.kind === kind ? 'page' : undefined}>
              {item.label}
            </Link>
          ))}
        </div>
      </nav>

      <div className={styles.live} role="status">
        {notice && notice.kind === kind ? (
          <p className={styles.toast}>
            <span>{notice.message}</span>
            {notice.undo ? (
              <button type="button" className={styles.linkButton} disabled={busy !== null}
                onClick={() => void undo(notice.undo!, notice.kind)}>
                Desfazer
              </button>
            ) : null}
          </p>
        ) : null}
        {recalcRun > 0 ? <p className={styles.recalculating}>{NOTE_RECALCULATING}</p> : null}
      </div>
      {actionError ? <p role="alert" className={styles.error}>{actionError}</p> : null}

      {list.status === 'loading' ? (
        <div className={styles.panel} role="status" aria-busy="true">
          <span className="sr-only">Carregando as recorrências…</span>
          <Skeleton width="lg" />
          <Skeleton width="md" />
          <Skeleton width="lg" />
        </div>
      ) : list.status === 'error' ? (
        <div role="alert" className={styles.panel}>
          <p className={styles.panelTitle}>Não conseguimos carregar suas recorrências agora.</p>
          <p className={styles.muted}>Seus dados estão seguros. Tente de novo em instantes.</p>
          <Button variant="secondary" onClick={list.retry}>Tentar novamente</Button>
        </div>
      ) : items.length === 0 ? (
        <section className={styles.panel} aria-labelledby="recurring-empty">
          <h2 id="recurring-empty" className={styles.panelTitle}>Nenhuma recorrência por enquanto</h2>
          <p className={styles.muted}>
            Ainda não identificamos recorrências. Elas aparecem depois de 3 meses de histórico.
          </p>
        </section>
      ) : (
        SECTIONS.map((section) => {
          const rows = items.filter((item) => item.status === section.status)
          if (rows.length === 0) return null
          const body = (
            <ul className={styles.list}>
              {rows.map((series) => (
                <SeriesItem key={series.id} series={series} names={names} busy={busy === series.id}
                  onConfirm={() => void run(series, 'confirm')} onIgnore={() => void run(series, 'ignore')}
                  onAdjust={(trigger) => setAdjusting({ series, trigger })} />
              ))}
            </ul>
          )
          return section.status === 'IGNORED' ? (
            <details key={section.status} className={styles.collapsible}>
              <summary className={styles.summary}>
                {section.title} <span className={styles.count}>{rows.length}</span>
              </summary>
              <p className={styles.muted}>{section.hint}</p>
              {body}
            </details>
          ) : (
            <section key={section.status} className={styles.section} aria-labelledby={`recurring-${section.status}`}>
              <div>
                <h2 id={`recurring-${section.status}`} className={styles.sectionTitle}>{section.title}</h2>
                <p className={styles.muted}>{section.hint}</p>
              </div>
              {body}
            </section>
          )
        })
      )}

      {adjusting ? (
        <RecurringDrawer
          key={adjusting.series.id}
          series={adjusting.series}
          kind={kind}
          trigger={adjusting.trigger}
          onClose={() => setAdjusting(null)}
          onDone={(outcome) => {
            setAdjusting(null)
            apply(outcome)
          }}
        />
      ) : null}
    </div>
  )
}

function SeriesItem({ series, names, busy, onConfirm, onIgnore, onAdjust }: {
  series: ForecastRecurringSeries
  names: { accounts: Map<string, string>; cards: Map<string, string> }
  busy: boolean
  onConfirm: () => void
  onIgnore: () => void
  onAdjust: (trigger: HTMLElement | null) => void
}) {
  const adjustButton = useRef<HTMLButtonElement>(null)
  const label = series.label ?? (series.direction === 'INCOME' ? 'Receita recorrente' : 'Despesa recorrente')
  const channel = series.channel.kind === 'CARD'
    ? `Cartão ${names.cards.get(series.channel.id) ?? ''}`.trim()
    : (names.accounts.get(series.channel.id) ?? 'Conta')
  const adjusted = series.origin === 'USER' || series.userAdjusted

  return (
    <li className={styles.item}>
      <div className={styles.itemHead}>
        <h3 className={styles.itemTitle}>{label}</h3>
        <span className={`${styles.badge} ${adjusted ? styles.badgeUser : ''}`}>
          {adjusted ? 'Ajustada por você' : 'Detectada'}
        </span>
      </div>
      <p className={styles.meta}>{channel} · todo dia {series.expectedDay}</p>
      <div className={styles.value}>
        <ProjectedValue value={seriesValue(series)} />
        <span className={styles.meta}>{confidenceText(series.detected.confidence)}</span>
      </div>
      {series.eligibility === 'STALE' ? <p className={styles.warning}>Sem ocorrência recente</p> : null}
      {series.eligibility === 'NOT_ELIGIBLE' ? <p className={styles.warning}>Não entra nas estimativas</p> : null}
      <div className={styles.actions}>
        {series.status !== 'CONFIRMED' ? (
          <Button variant={series.status === 'DETECTED' ? 'primary' : 'secondary'} disabled={busy} onClick={onConfirm}
            aria-label={`Confirmar ${label}`}>
            Confirmar
          </Button>
        ) : null}
        {series.status !== 'IGNORED' ? (
          <Button variant="secondary" disabled={busy} onClick={onIgnore} aria-label={`Ignorar ${label}`}>
            Ignorar
          </Button>
        ) : null}
        {series.status !== 'IGNORED' ? (
          <button ref={adjustButton} type="button" className={styles.linkButton} disabled={busy}
            aria-haspopup="dialog" aria-label={`Ajustar ${label}`} onClick={() => onAdjust(adjustButton.current)}>
            Ajustar
          </button>
        ) : null}
      </div>
    </li>
  )
}
