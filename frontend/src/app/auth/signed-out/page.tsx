import type { Metadata } from 'next'
import styles from '../error/page.module.css'

export const metadata: Metadata = { title: 'Você saiu' }

/** Destino fixo do logout (ADR-0008). A sessão local e a do IdP já foram encerradas. */
export default function SignedOutPage() {
  return (
    <main className={styles.wrap}>
      <h1 className={styles.title}>Você saiu da sua conta</h1>
      <p className={styles.text}>Sua sessão foi encerrada neste dispositivo. Para voltar, entre novamente.</p>
      {/* <a> comum: /auth/login é um Route Handler que redireciona para o IdP (navegação completa). */}
      {/* eslint-disable-next-line @next/next/no-html-link-for-pages */}
      <a href="/auth/login" className={styles.cta}>
        Entrar novamente
      </a>
    </main>
  )
}
