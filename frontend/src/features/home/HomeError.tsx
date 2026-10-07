import { CircleAlert } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import type { OverviewErrorKind } from './useOverview'
import styles from './HomeError.module.css'

interface Props {
  kind: OverviewErrorKind
  onRetry: () => void
  onReset: () => void
}

/** Mensagens fixas: nada do backend (stack, SQL, IDs, mensagens internas) é exibido. */
export function HomeError({ kind, onRetry, onReset }: Props) {
  const invalid = kind === 'invalid-period'
  return (
    <section className={styles.error} role="alert" aria-labelledby="home-error-title">
      <span className={styles.icon} aria-hidden="true">
        <CircleAlert size={24} strokeWidth={1.75} />
      </span>
      <h2 id="home-error-title" className={styles.title}>
        {invalid ? 'Esse período não pôde ser aplicado' : 'Não foi possível carregar seu resumo financeiro'}
      </h2>
      <p className={styles.text}>
        {invalid
          ? 'Confira as datas escolhidas ou volte para o mês atual.'
          : 'Seus dados continuam seguros. Tente novamente em instantes.'}
      </p>
      {invalid ? <Button onClick={onReset}>Voltar para este mês</Button> : <Button onClick={onRetry}>Tentar novamente</Button>}
    </section>
  )
}
