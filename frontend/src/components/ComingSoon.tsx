import { Button } from '@/components/ui/Button'
import styles from './ComingSoon.module.css'

/** Estado "Unavailable" do Design System: o recurso ainda não existe nesta versão (não é um erro). */
export function ComingSoon({ title }: { title: string }) {
  return (
    <section className={styles.wrap} aria-labelledby="coming-soon-title">
      <span className={styles.badge}>Em breve</span>
      <h1 id="coming-soon-title" className={styles.title}>
        {title}
      </h1>
      <p className={styles.text}>Esta área ainda não está disponível nesta versão. Seus dados na Home continuam atualizados.</p>
      <div className={styles.actions}>
        <Button href="/home">Voltar ao início</Button>
      </div>
    </section>
  )
}
