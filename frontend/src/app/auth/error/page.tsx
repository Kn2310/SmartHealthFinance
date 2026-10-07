import type { Metadata } from 'next'
import styles from './page.module.css'

export const metadata: Metadata = { title: 'Não foi possível entrar' }

/** Mensagem genérica de propósito: o motivo técnico (reason) não é exibido. */
export default function AuthErrorPage() {
  return (
    <main className={styles.wrap}>
      <h1 className={styles.title}>Não foi possível entrar</h1>
      <p className={styles.text}>
        Algo deu errado ao confirmar seu acesso. Nenhum dado foi alterado. Tente novamente em instantes.
      </p>
      {/* <a> comum: /auth/login é um Route Handler que redireciona para o IdP (navegação completa). */}
      {/* eslint-disable-next-line @next/next/no-html-link-for-pages */}
      <a href="/auth/login" className={styles.cta}>
        Tentar novamente
      </a>
    </main>
  )
}
