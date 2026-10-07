import { Skeleton } from '@/components/ui/Skeleton'
import styles from './HomeView.module.css'

/**
 * Mesma estrutura da Home pronta (hero, fluxo, contas, recentes): sem spinner central, sem salto de layout.
 * `header` desliga o cabeçalho fantasma quando a Home já renderiza o cabeçalho real (seletor utilizável).
 */
export function HomeSkeleton({ header = true }: { header?: boolean }) {
  return (
    <div role="status" aria-live="polite" aria-busy="true">
      <span className="sr-only">Carregando seu resumo financeiro…</span>
      {header ? (
        <div className={styles.header}>
          <div className={styles.skeletonStack}>
            <Skeleton width="sm" height="title" />
            <Skeleton width="md" height="caption" />
          </div>
        </div>
      ) : null}
      <div className={styles.grid}>
        <div className={`${styles.hero} ${styles.skeletonCard}`}>
          <Skeleton width="xs" height="caption" />
          <Skeleton width="md" height="value" />
          <Skeleton width="sm" height="caption" />
        </div>
        <div className={`${styles.cashflow} ${styles.skeletonCard}`}>
          <Skeleton width="sm" height="body" />
          <Skeleton height="title" />
          <Skeleton height="body" />
          <Skeleton height="body" />
        </div>
        <div className={`${styles.accounts} ${styles.skeletonCard}`}>
          <Skeleton width="xs" height="body" />
          <Skeleton height="row" />
          <Skeleton height="row" />
        </div>
        <div className={`${styles.recent} ${styles.skeletonCard}`}>
          <Skeleton width="sm" height="body" />
          <Skeleton height="row" />
          <Skeleton height="row" />
          <Skeleton height="row" />
        </div>
      </div>
    </div>
  )
}
