'use client'

import { CircleAlert, CircleCheck, CircleSlash, FileText, Hourglass } from 'lucide-react'
import { useState } from 'react'
import { Button } from '@/components/ui/Button'
import { Skeleton } from '@/components/ui/Skeleton'
import { formatPeriodRange } from '@/lib/format/date'
import { cancelImport, confirmImport, type ImportBatch } from './api'
import { formatFileSize } from './file-check'
import { useImportBatch } from './hooks'
import { failureReasonMessage, plural, requestFailureMessage } from './messages'
import { RecordList } from './RecordList'
import { Steps } from './Steps'
import styles from './Import.module.css'

/** Etapas 2 e 3 de um batch já enviado, decididas pelo status do backend. */
export function BatchView({ id }: { id: string }) {
  const batch = useImportBatch(id)

  if (batch.status === 'loading') {
    return (
      <div className={styles.panel} role="status" aria-busy="true">
        <span className="sr-only">Carregando a importação…</span>
        <Skeleton width="md" height="title" />
        <Skeleton height="row" />
        <Skeleton height="row" />
      </div>
    )
  }

  if (batch.status === 'error') {
    const notFound = batch.failure.code === 'IMPORT_NOT_FOUND'
    return (
      <section className={styles.notice} role="alert" aria-labelledby="batch-error-title">
        <h2 id="batch-error-title" className={styles.noticeTitle}>
          {notFound ? 'Importação não encontrada' : 'Não foi possível carregar a importação'}
        </h2>
        <p className={styles.noticeText}>{requestFailureMessage(batch.failure.code)}</p>
        {notFound ? <Button href="/import">Importar um arquivo</Button> : <Button onClick={batch.retry}>Tentar novamente</Button>}
      </section>
    )
  }

  const data = batch.data
  switch (data.status) {
    case 'PREVIEW':
      return <Preview batch={data} onChange={batch.replace} />
    case 'CONFIRMED':
    case 'PROCESSING':
      return <Processing batch={data} />
    case 'COMPLETED':
      return <Completed batch={data} />
    default:
      return <Ended batch={data} />
  }
}

function Summary({ batch }: { batch: ImportBatch }) {
  return (
    <section className={styles.summary} aria-labelledby="summary-title">
      <div className={styles.fileCard}>
        <span className={styles.fileIcon} aria-hidden="true">
          <FileText size={20} strokeWidth={1.75} />
        </span>
        <span className={styles.fileText}>
          <h2 id="summary-title" className={styles.fileName}>
            {batch.fileName}
          </h2>
          <span className={styles.fileMeta}>
            {[
              batch.format,
              formatFileSize(batch.fileSize),
              batch.period ? formatPeriodRange(batch.period.from, batch.period.to) : null,
            ]
              .filter(Boolean)
              .join(' · ')}
          </span>
        </span>
      </div>
      <dl className={styles.counts}>
        {batch.status === 'COMPLETED' ? (
          <Count tone="success" label="Importadas" value={batch.lines.imported} />
        ) : (
          <Count tone="teal" label="Novas para importar" value={batch.lines.valid} />
        )}
        <Count tone="neutral" label="Já importadas antes" value={batch.lines.duplicate} />
        <Count tone="warning" label="Com problema" value={batch.lines.invalid} />
      </dl>
    </section>
  )
}

function Count({ label, value, tone }: { label: string; value: number; tone: 'success' | 'teal' | 'neutral' | 'warning' }) {
  return (
    <div className={`${styles.count} ${styles[tone]}`}>
      <dt className={styles.countLabel}>{label}</dt>
      <dd className={styles.countValue}>{value.toLocaleString('pt-BR')}</dd>
    </div>
  )
}

function Preview({ batch, onChange }: { batch: ImportBatch; onChange: (batch: ImportBatch) => void }) {
  const [busy, setBusy] = useState<'confirm' | 'cancel' | null>(null)
  const [error, setError] = useState<string | null>(null)
  const nothingNew = batch.lines.valid === 0

  async function act(action: 'confirm' | 'cancel') {
    setBusy(action)
    setError(null)
    const result = action === 'confirm' ? await confirmImport(batch.id) : await cancelImport(batch.id)
    setBusy(null)
    if (result.ok) onChange(result.data)
    else setError(requestFailureMessage(result.code))
  }

  return (
    <>
      <Steps current={2} />
      <Summary batch={batch} />

      {batch.sameFileImportedBefore ? (
        <p className={styles.info} role="note">
          Este arquivo já foi importado nesta conta. As movimentações repetidas são reconhecidas e não entram de novo.
        </p>
      ) : null}
      {nothingNew ? (
        <p className={styles.info} role="note">
          Não há movimentações novas neste arquivo: tudo o que ele traz já está na sua conta ou tem problema.
        </p>
      ) : (
        <p className={styles.info} role="note">
          {plural(batch.lines.valid, 'movimentação nova entra', 'movimentações novas entram')} como realizada
          {batch.lines.valid === 1 ? '' : 's'}, marcada{batch.lines.valid === 1 ? '' : 's'} como “Importado”. Linhas com problema
          ficam de fora.
        </p>
      )}

      <RecordList batch={batch} />

      {error ? (
        <p className={styles.error} role="alert">
          <CircleAlert size={18} strokeWidth={1.75} aria-hidden="true" />
          <span>{error}</span>
        </p>
      ) : null}

      <div className={styles.actions}>
        <Button variant="secondary" onClick={() => act('cancel')} disabled={busy !== null}>
          {busy === 'cancel' ? 'Descartando…' : 'Descartar'}
        </Button>
        <Button onClick={() => act('confirm')} disabled={busy !== null || nothingNew}>
          {busy === 'confirm'
            ? 'Confirmando…'
            : nothingNew
              ? 'Nada para importar'
              : `Importar ${plural(batch.lines.valid, 'movimentação', 'movimentações')}`}
        </Button>
      </div>
    </>
  )
}

function Processing({ batch }: { batch: ImportBatch }) {
  return (
    <>
      <Steps current={3} />
      <section className={styles.notice} role="status" aria-live="polite" aria-labelledby="processing-title">
        <span className={styles.noticeIcon} aria-hidden="true">
          <Hourglass size={24} strokeWidth={1.75} />
        </span>
        <h2 id="processing-title" className={styles.noticeTitle}>
          Importando {plural(batch.lines.valid, 'movimentação', 'movimentações')}…
        </h2>
        <p className={styles.noticeText}>
          Costuma levar poucos segundos. Você pode sair desta página: a importação continua e o resultado aparece aqui
          quando você voltar.
        </p>
      </section>
      <Summary batch={batch} />
    </>
  )
}

function Completed({ batch }: { batch: ImportBatch }) {
  return (
    <>
      <Steps current={3} done />
      <section className={`${styles.notice} ${styles.noticeSuccess}`} aria-labelledby="completed-title">
        <span className={styles.noticeIcon} aria-hidden="true">
          <CircleCheck size={24} strokeWidth={1.75} />
        </span>
        <h2 id="completed-title" className={styles.noticeTitle}>
          {batch.lines.imported === 0
            ? 'Nenhuma movimentação nova'
            : `${plural(batch.lines.imported, 'movimentação importada', 'movimentações importadas')}`}
        </h2>
        <p className={styles.noticeText}>
          {batch.lines.imported === 0
            ? 'Tudo o que o arquivo traz já estava na sua conta.'
            : 'Elas já aparecem no seu saldo e nas movimentações, marcadas como “Importado”.'}
          {batch.lines.duplicate > 0 ? ` ${plural(batch.lines.duplicate, 'repetida foi ignorada', 'repetidas foram ignoradas')}.` : ''}
        </p>
        <div className={styles.actions}>
          <Button href="/import" variant="secondary">
            Importar outro arquivo
          </Button>
          <Button href="/home">Ver meu panorama</Button>
        </div>
      </section>
      <Summary batch={batch} />
      <RecordList batch={batch} />
    </>
  )
}

function Ended({ batch }: { batch: ImportBatch }) {
  const failed = batch.status === 'FAILED'
  const title = failed
    ? 'A importação não foi concluída'
    : batch.status === 'CANCELLED'
      ? 'Importação descartada'
      : batch.status === 'EXPIRED'
        ? 'Esta prévia expirou'
        : 'Não conseguimos exibir esta importação'
  const text = failed
    ? failureReasonMessage(batch.failureReason)
    : batch.status === 'CANCELLED'
      ? 'Nada foi importado e as linhas do arquivo foram apagadas.'
      : batch.status === 'EXPIRED'
        ? 'Prévias não confirmadas em 24 horas são descartadas. Envie o arquivo de novo.'
        : 'Atualize a página para carregar a versão mais recente do app.'

  return (
    <section
      className={`${styles.notice} ${failed ? styles.noticeDanger : ''}`}
      role={failed ? 'alert' : undefined}
      aria-labelledby="ended-title"
    >
      <span className={styles.noticeIcon} aria-hidden="true">
        {failed ? <CircleAlert size={24} strokeWidth={1.75} /> : <CircleSlash size={24} strokeWidth={1.75} />}
      </span>
      <h2 id="ended-title" className={styles.noticeTitle}>
        {title}
      </h2>
      <p className={styles.noticeText}>{text}</p>
      <div className={styles.actions}>
        <Button href="/home" variant="secondary">
          Voltar ao início
        </Button>
        <Button href="/import">Importar um arquivo</Button>
      </div>
    </section>
  )
}
