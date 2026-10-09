'use client'

import { useSearchParams } from 'next/navigation'
import { isUuid } from '@/lib/uuid'
import { BatchView } from './BatchView'
import { UploadStep } from './UploadStep'
import styles from './Import.module.css'

/**
 * Fluxo de importação (specs/03-ux/core-user-flows: arquivo → validação → preview → confirmação → processamento
 * → resultado). O batch fica na URL (`?id=`): recarregar a página retoma de onde parou.
 */
export function ImportView() {
  const searchParams = useSearchParams()
  const id = searchParams.get('id')
  const accountId = searchParams.get('accountId')

  return (
    <div className={styles.page}>
      <header className={styles.pageHeader}>
        <h1 className={styles.pageTitle}>Importar extrato</h1>
        <p className={styles.pageSubtitle}>
          Traga as movimentações de um arquivo OFX ou CSV do seu banco. Nada é gravado antes da sua confirmação.
        </p>
      </header>
      {isUuid(id) ? (
        <BatchView key={id} id={id} />
      ) : (
        <UploadStep preselectedAccountId={isUuid(accountId) ? accountId : null} />
      )}
    </div>
  )
}
