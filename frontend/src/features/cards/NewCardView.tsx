'use client'

import { useRouter } from 'next/navigation'
import { Skeleton } from '@/components/ui/Skeleton'
import { useAccounts } from '@/features/transactions/hooks'
import { createCard } from './api'
import { CardForm } from './CardForm'
import { BackLink } from './states'
import accountStyles from '@/features/accounts/Accounts.module.css'

/**
 * "Adicionar cartão" (ADR-0026 §10). Vindo de `/cards`, volta ao cartão criado; vindo do lançamento, volta para a
 * compra no cartão.
 */
export function NewCardView({ from = null }: { from?: 'cards' | null }) {
  const router = useRouter()
  const accounts = useAccounts()
  const back = from === 'cards' ? { href: '/cards', label: 'Cartões' } : { href: '/transactions/new?type=CARD_PURCHASE', label: 'Nova transação' }

  return (
    <div className={accountStyles.narrow}>
      <BackLink href={back.href}>{back.label}</BackLink>
      <header className={accountStyles.pageHeader}>
        <h1 className={accountStyles.pageTitle}>Adicionar cartão</h1>
        <p className={accountStyles.pageSubtitle}>
          Informe só o necessário para acompanhar faturas e parcelas. Limite e datas ajudam a calcular a utilização.
        </p>
      </header>
      {accounts.status === 'loading' ? (
        <div className={accountStyles.panel} role="status" aria-busy="true">
          <span className="sr-only">Carregando suas contas…</span>
          <Skeleton height="row" />
          <Skeleton height="row" />
        </div>
      ) : (
        <section className={accountStyles.panel} aria-labelledby="new-card-title">
          <h2 id="new-card-title" className={accountStyles.sectionTitle}>
            Dados do cartão
          </h2>
          <CardForm
            accounts={accounts.status === 'success' ? accounts.data.filter((account) => account.status === 'ACTIVE') : []}
            submitLabel="Cadastrar cartão"
            submittingLabel="Cadastrando…"
            cancelHref={back.href}
            keepBusyOnSuccess
            onSubmit={async (fields) => {
              const result = await createCard(fields)
              if (!result.ok) return result
              router.push(from === 'cards' ? `/cards/${result.data.id}` : '/transactions/new?type=CARD_PURCHASE')
              return null
            }}
          />
        </section>
      )}
    </div>
  )
}
