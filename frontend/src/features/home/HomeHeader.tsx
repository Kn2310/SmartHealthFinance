import { Download } from 'lucide-react'
import type { ReactNode } from 'react'
import { Button } from '@/components/ui/Button'
import styles from './HomeView.module.css'

interface Props {
  firstName: string
  subtitle: string
  /** Ação "Importar extrato" do cabeçalho (design/specs/03-screens). Sem conta não há onde importar. */
  showImport?: boolean
  children?: ReactNode
}

/** Contexto (quem e qual período) + seletor de período e importação. Sem painel de controles. */
export function HomeHeader({ firstName, subtitle, showImport = true, children }: Props) {
  return (
    <header className={styles.header}>
      <div>
        <h1 className={styles.greeting}>{firstName ? `Olá, ${firstName}` : 'Olá'}</h1>
        <p className={styles.subtitle}>{subtitle}</p>
      </div>
      {children || showImport ? (
        <div className={styles.headerActions}>
          {children}
          {showImport ? (
            <Button href="/import" variant="secondary">
              <Download size={18} strokeWidth={1.75} aria-hidden="true" />
              Importar extrato
            </Button>
          ) : null}
        </div>
      ) : null}
    </header>
  )
}
