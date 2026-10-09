'use client'

import { ChevronLeft, PlugZap } from 'lucide-react'
import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { AccountForm } from './AccountForm'
import { createAccount, type AccountFields } from './api'
import { cancelDestination, destinationAfterCreate, type ReturnTo } from './return-to'
import styles from './Accounts.module.css'

const EMPTY: AccountFields = { name: '', type: 'CHECKING', institutionName: null, includedInTotal: true }

/**
 * "Criar conta manual" de D-AddAccount, como página. A conta nasce sem saldo: ele vem das movimentações
 * (importação ou lançamento), nunca de um campo editável (ADR-0004 §4).
 */
export function NewAccountView({ returnTo }: { returnTo: ReturnTo | null }) {
  const router = useRouter()
  const back = cancelDestination(returnTo)

  async function submit(fields: AccountFields) {
    const result = await createAccount(fields)
    if (!result.ok) return result
    router.push(destinationAfterCreate(returnTo, result.data.id))
    return 'leaving' as const
  }

  return (
    <div className={styles.narrow}>
      <Link href={back} className={styles.back}>
        <ChevronLeft size={18} strokeWidth={1.75} aria-hidden="true" />
        {returnTo === 'import' ? 'Importar extrato' : 'Contas'}
      </Link>
      <header className={styles.pageHeader}>
        <h1 className={styles.pageTitle}>Adicionar conta</h1>
        <p className={styles.pageSubtitle}>
          {returnTo === 'import'
            ? 'Crie a conta que vai receber as movimentações. Em seguida, você volta para a importação com ela selecionada.'
            : 'Crie uma conta manual. O saldo vem das movimentações que você importar ou registrar.'}
        </p>
      </header>

      <section className={styles.panel} aria-labelledby="new-account-title">
        <h2 id="new-account-title" className={styles.sectionTitle}>
          Dados da conta
        </h2>
        <AccountForm
          initial={EMPTY}
          submitLabel={returnTo === 'import' ? 'Criar e continuar' : 'Criar conta'}
          submittingLabel="Criando conta…"
          onSubmit={submit}
          cancelHref={back}
        >
          <div className={styles.unavailable}>
            <span className={styles.unavailableIcon} aria-hidden="true">
              <PlugZap size={20} strokeWidth={1.75} />
            </span>
            <p>
              <span className={styles.soonBadge}>Em breve</span> Conexão automática com bancos (Open Finance) estará
              disponível em uma versão futura.
            </p>
          </div>
        </AccountForm>
      </section>
    </div>
  )
}
