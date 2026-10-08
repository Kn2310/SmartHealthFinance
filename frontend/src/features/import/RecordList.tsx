'use client'

import { useState } from 'react'
import { Button } from '@/components/ui/Button'
import { Money } from '@/components/ui/Money'
import { Skeleton } from '@/components/ui/Skeleton'
import { formatShortDate } from '@/lib/format/date'
import type { ImportBatch, ImportRecord, RecordStatus } from './api'
import { useImportRecords } from './hooks'
import { lineIssueMessage, recordStatusLabel, requestFailureMessage } from './messages'
import styles from './Import.module.css'

const PAGE_SIZE = 50

interface Filter {
  status: RecordStatus | null
  label: string
  count: (batch: ImportBatch) => number
}

/** Filtros do preview (o que vai entrar) e do resultado (o que entrou). */
function filters(batch: ImportBatch): Filter[] {
  const main: Filter =
    batch.status === 'COMPLETED'
      ? { status: 'IMPORTED', label: 'Importadas', count: (b) => b.lines.imported }
      : { status: 'VALID', label: 'Novas', count: (b) => b.lines.valid }
  return [
    { status: null, label: 'Todas', count: (b) => b.lines.total },
    main,
    { status: 'DUPLICATE', label: 'Já importadas', count: (b) => b.lines.duplicate },
    { status: 'INVALID', label: 'Com problema', count: (b) => b.lines.invalid },
  ]
}

/** Linhas do arquivo em ordem, paginadas no servidor. Só exibe: nenhum valor é recalculado aqui. */
export function RecordList({ batch }: { batch: ImportBatch }) {
  const [status, setStatus] = useState<RecordStatus | null>(null)
  const [page, setPage] = useState(0)
  // Recarrega quando o status do batch muda (ex.: VALID → IMPORTED depois do processamento).
  const records = useImportRecords(batch.id, status, page, batch.status)

  const total = records.status === 'success' ? records.data.totalItems : 0
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE))

  return (
    <section className={styles.records} aria-labelledby="records-title">
      <h2 id="records-title" className={styles.sectionTitle}>
        Linhas do arquivo
      </h2>
      <div className={styles.filters} role="group" aria-label="Filtrar linhas">
        {filters(batch).map((filter) => (
          <button
            key={filter.label}
            type="button"
            className={styles.filter}
            aria-pressed={status === filter.status}
            onClick={() => {
              setStatus(filter.status)
              setPage(0)
            }}
          >
            {filter.label} <span className={styles.filterCount}>{filter.count(batch).toLocaleString('pt-BR')}</span>
          </button>
        ))}
      </div>

      {records.status === 'loading' ? (
        <div role="status" aria-busy="true" className={styles.recordSkeleton}>
          <span className="sr-only">Carregando linhas…</span>
          <Skeleton height="row" />
          <Skeleton height="row" />
          <Skeleton height="row" />
        </div>
      ) : records.status === 'error' ? (
        <div className={styles.inlineError} role="alert">
          <p>{requestFailureMessage(records.failure.code)}</p>
          <Button variant="secondary" onClick={records.retry}>
            Tentar novamente
          </Button>
        </div>
      ) : records.data.items.length === 0 ? (
        <p className={styles.hint}>Nenhuma linha neste filtro.</p>
      ) : (
        <>
          <ul className={styles.recordList}>
            {records.data.items.map((record) => (
              <RecordItem key={record.lineNumber} record={record} />
            ))}
          </ul>
          {pages > 1 ? (
            <nav className={styles.pagination} aria-label="Páginas de linhas">
              <Button variant="secondary" onClick={() => setPage((p) => p - 1)} disabled={page === 0}>
                Anteriores
              </Button>
              <span className={styles.pageInfo} aria-live="polite">
                Página {page + 1} de {pages}
              </span>
              <Button variant="secondary" onClick={() => setPage((p) => p + 1)} disabled={page + 1 >= pages}>
                Próximas
              </Button>
            </nav>
          ) : null}
        </>
      )}
    </section>
  )
}

const BADGE_TONE: Partial<Record<string, string>> = {
  VALID: 'teal',
  IMPORTED: 'success',
  DUPLICATE: 'neutral',
  INVALID: 'warning',
}

function RecordItem({ record }: { record: ImportRecord }) {
  const invalid = record.status === 'INVALID' || record.amount === null
  return (
    <li className={styles.record}>
      <span className={styles.recordText}>
        <span className={styles.recordTitle}>
          {invalid ? lineIssueMessage(record.issue?.code) : record.description}
        </span>
        <span className={styles.recordMeta}>
          Linha {record.lineNumber}
          {record.occurredOn ? (
            <>
              {' · '}
              <time dateTime={record.occurredOn}>{formatShortDate(record.occurredOn)}</time>
            </>
          ) : null}
        </span>
      </span>
      <span className={styles.recordSide}>
        {invalid ? null : (
          <Money
            value={record.amount}
            sign={record.direction === 'INFLOW' ? 'inflow' : record.direction === 'OUTFLOW' ? 'outflow' : 'never'}
            className={styles.recordAmount}
          />
        )}
        <span className={`${styles.badge} ${styles[BADGE_TONE[record.status] ?? 'neutral']}`}>
          {recordStatusLabel(record.status)}
        </span>
      </span>
    </li>
  )
}
