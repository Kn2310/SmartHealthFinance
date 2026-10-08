import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { Overview, OverviewTransaction } from '@/lib/api/types'
import { axeViolations } from '@/test/a11y'
import { HomeContent } from './HomeContent'
import { brl, noAccountsOverview, noActivityOverview, noTransactionsOverview, readyOverview } from './fixtures'

/** Texto de um elemento sem distinguir espaço comum de não separável. */
const text = (el: Element | null) => el?.textContent?.replace(/ /g, ' ') ?? ''

const pending = (overrides: Partial<OverviewTransaction> = {}): OverviewTransaction => ({
  id: 'p1',
  type: 'EXPENSE',
  flow: 'OUTFLOW',
  adjustmentDirection: null,
  status: 'PENDING',
  amount: brl('89.90'),
  occurredOn: '2026-09-25',
  description: 'Assinatura',
  account: { id: 'a1', name: 'Conta Aurora' },
  destinationAccount: null,
  refundOfTransactionId: null,
  source: 'MANUAL',
  ...overrides,
})

describe('HomeContent — READY', () => {
  it('mostra saldo, fluxo, contas e movimentações', () => {
    render(<HomeContent overview={readyOverview()} />)

    const balance = screen.getByRole('region', { name: 'Saldo total' })
    expect(within(balance).getByText('R$ 7.627,52')).toBeInTheDocument()
    expect(within(balance).getByText(/1 movimentação pendente no período ainda não entra no saldo/)).toBeInTheDocument()

    const cash = screen.getByRole('region', { name: 'Fluxo do período' })
    expect(within(cash).getByText('+ R$ 6.800,00')).toBeInTheDocument()
    expect(within(cash).getByText('− R$ 3.846,20')).toBeInTheDocument()
    expect(within(cash).getByText('+ R$ 3.003,80')).toBeInTheDocument()

    const accounts = screen.getByRole('region', { name: 'Contas' })
    expect(within(accounts).getByText('Conta Aurora')).toBeInTheDocument()
    expect(within(accounts).getByText('Conta corrente · Banco Aurora')).toBeInTheDocument()
    expect(within(accounts).getByText('Fora do saldo total')).toBeInTheDocument()

    const recent = screen.getByRole('region', { name: 'Movimentações recentes' })
    expect(within(recent).getAllByRole('listitem')).toHaveLength(4)
  })

  it('a data do saldo é a data-base ("Saldo em"), nunca "Atualizado em"', () => {
    render(<HomeContent overview={readyOverview()} />)
    const balance = screen.getByRole('region', { name: 'Saldo total' })
    expect(within(balance).getByText(/Saldo em 27 set\. · 2 contas/)).toBeInTheDocument()
    expect(balance.textContent).not.toMatch(/Atualizado/)
  })

  it('mantém reembolso e transferência separados de receita e despesa (dt/dd associados)', () => {
    render(<HomeContent overview={readyOverview()} />)
    const cash = screen.getByRole('region', { name: 'Fluxo do período' })

    const refundTerm = within(cash).getByText('Reembolsos').closest('dt')!
    expect(refundTerm).toHaveTextContent(/não contam como receita/)
    expect(text(refundTerm.nextElementSibling)).toBe('+ R$ 50,00mais R$ 50,00')
    expect(refundTerm.nextElementSibling?.tagName).toBe('DD')
    expect(refundTerm.parentElement?.parentElement?.tagName).toBe('DL')

    expect(within(cash).getByText('Transferências entre contas')).toBeInTheDocument()
    expect(within(cash).getByText(/Não\s+contam como receita nem despesa/)).toBeInTheDocument()
  })

  it('transferência não tem sinal e informa origem → destino; pendente é identificada', () => {
    render(<HomeContent overview={readyOverview()} />)
    const recent = screen.getByRole('region', { name: 'Movimentações recentes' })

    const transfer = within(recent).getByText(/Conta Aurora → Reserva/).closest('li') as HTMLElement
    expect(within(transfer).getByText('R$ 500,00')).toBeInTheDocument()
    expect(within(transfer).getAllByText('Transferência').length).toBeGreaterThan(0)

    const refund = within(recent).getByText('Estorno loja').closest('li') as HTMLElement
    expect(within(refund).getByText('Pendente')).toBeInTheDocument()
    expect(within(refund).getByText('Reembolso')).toBeInTheDocument()
  })

  it('o sinal e o tipo comunicam direção sem depender de cor', () => {
    render(<HomeContent overview={readyOverview()} />)
    const expense = screen.getByText('Mercado').closest('li') as HTMLElement
    expect(within(expense).getByText('− R$ 420,00')).toBeInTheDocument()
    expect(within(expense).getByText('menos R$ 420,00')).toHaveClass('sr-only')
    expect(within(expense).getByText('Despesa')).toBeInTheDocument()
  })

  it('movimentação importada leva o selo "Importado"; manual não (Trust UX, ADR-0009)', () => {
    const overview = readyOverview()
    overview.recentTransactions = overview.recentTransactions.map((t) =>
      t.id === 't2' ? { ...t, source: 'IMPORT' as const } : t,
    )
    render(<HomeContent overview={overview} />)

    const imported = screen.getByText('Mercado').closest('li') as HTMLElement
    const manual = screen.getByText('Salário').closest('li') as HTMLElement
    expect(within(imported).getByText('Importado')).toBeInTheDocument()
    expect(within(manual).queryByText('Importado')).not.toBeInTheDocument()
  })

  it('saldo total negativo aparece com sinal e por extenso para leitor de tela', () => {
    const overview = readyOverview()
    overview.summary.totalBalance = brl('-1234.56')
    render(<HomeContent overview={overview} />)
    const balance = screen.getByRole('region', { name: 'Saldo total' })
    expect(within(balance).getByText('− R$ 1.234,56')).toBeInTheDocument()
    expect(within(balance).getByText('menos R$ 1.234,56')).toHaveClass('sr-only')
  })

  it('valores grandes preservam todos os dígitos', () => {
    const overview = readyOverview()
    overview.summary.totalBalance = brl('123456789012.34')
    overview.cashFlow!.expense = brl('98765432.10')
    render(<HomeContent overview={overview} />)
    expect(screen.getByText('R$ 123.456.789.012,34')).toBeInTheDocument()
    expect(screen.getByText('− R$ 98.765.432,10')).toBeInTheDocument()
  })

  it('zero real no fluxo é R$ 0,00; null no fluxo é "—" (sem dados)', () => {
    const overview = readyOverview()
    overview.cashFlow!.refunds = brl('0.00')
    overview.cashFlow!.net = null
    render(<HomeContent overview={overview} />)
    const cash = screen.getByRole('region', { name: 'Fluxo do período' })
    expect(text(within(cash).getByText('Reembolsos').closest('dt')!.nextElementSibling)).toBe('R$ 0,00')
    expect(text(within(cash).getByText('Resultado líquido').closest('dt')!.nextElementSibling)).toBe('—sem dados')
  })

  it('sem violações de acessibilidade (axe)', async () => {
    const { container } = render(<HomeContent overview={readyOverview()} />)
    expect(await axeViolations(container)).toEqual([])
  })
})

describe('HomeContent — enums desconhecidos (backend evoluiu)', () => {
  it('tipo, flow e status desconhecidos: rótulo e ícone neutros, sem sinal, sem quebrar', () => {
    const overview = readyOverview({
      recentTransactions: [
        pending({
          id: 'x1',
          type: 'CASHBACK' as never,
          flow: 'SIDEWAYS' as never,
          status: 'IN_REVIEW' as never,
          description: null,
          amount: brl('10.00'),
        }),
      ],
    })
    render(<HomeContent overview={overview} />)
    const item = screen.getAllByText('Movimentação')[0]!.closest('li') as HTMLElement
    expect(within(item).getByText('R$ 10,00')).toBeInTheDocument()
    expect(item.textContent).not.toMatch(/[+−] R\$/)
    expect(within(item).getByText('Não realizada')).toBeInTheDocument()
  })

  it('tipo de conta desconhecido: "Conta" com ícone neutro', () => {
    const overview = readyOverview()
    overview.accounts[0] = { ...overview.accounts[0]!, type: 'INVESTMENT' as never, institutionName: null }
    render(<HomeContent overview={overview} />)
    const accounts = screen.getByRole('region', { name: 'Contas' })
    expect(within(accounts).getByText('Conta')).toBeInTheDocument()
  })

  it('estado desconhecido: mensagem neutra, nenhum valor exibido', () => {
    const overview = { ...readyOverview(), state: 'FORECAST_READY' } as unknown as Overview
    render(<HomeContent overview={overview} />)
    expect(screen.getByRole('heading', { name: 'Não conseguimos exibir este resumo' })).toBeInTheDocument()
    expect(screen.queryByText(/R\$/)).not.toBeInTheDocument()
  })
})

describe('HomeContent — NO_ACCOUNTS', () => {
  it('explica a ausência de contas e oferece "Adicionar conta"', async () => {
    const { container } = render(<HomeContent overview={noAccountsOverview()} />)

    expect(screen.getByRole('heading', { name: 'Você ainda não tem contas' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Adicionar conta' })).toHaveAttribute('href', '/accounts/new')
    expect(screen.queryByText(/Saldo total/)).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Fluxo do período' })).not.toBeInTheDocument()
    expect(screen.queryByText(/R\$/)).not.toBeInTheDocument()
    expect(await axeViolations(container)).toEqual([])
  })
})

describe('HomeContent — NO_TRANSACTIONS', () => {
  it('não apresenta R$ 0,00 como saldo e convida à primeira movimentação', async () => {
    const { container } = render(<HomeContent overview={noTransactionsOverview()} />)

    expect(screen.getByRole('heading', { name: 'Registre sua primeira movimentação' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Registrar movimentação' })).toHaveAttribute('href', '/transactions/new')
    expect(screen.getByRole('link', { name: 'Importar extrato' })).toHaveAttribute('href', '/import')
    expect(screen.queryByText(/R\$/)).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Saldo total' })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Movimentações pendentes' })).not.toBeInTheDocument()
    expect(await axeViolations(container)).toEqual([])
  })

  it('mostra as contas existentes com saldo ausente ("—"), não zero', () => {
    render(<HomeContent overview={noTransactionsOverview()} />)
    const accounts = screen.getByRole('region', { name: 'Contas' })
    expect(within(accounts).getByText('Conta Aurora')).toBeInTheDocument()
    expect(within(accounts).getAllByText('sem dados')).toHaveLength(2)
  })

  it('PENDING devolvidos pelo backend aparecem como pendentes, separados do realizado', () => {
    const overview = noTransactionsOverview()
    overview.summary.pendingTransactions = 1
    overview.recentTransactions = [pending()]
    render(<HomeContent overview={overview} />)

    const list = screen.getByRole('region', { name: 'Movimentações pendentes' })
    expect(within(list).getByText('Ainda não entram no saldo nem no fluxo do período.')).toBeInTheDocument()
    const item = within(list).getByText('Assinatura').closest('li') as HTMLElement
    expect(within(item).getByText('Pendente')).toBeInTheDocument()
    expect(within(item).getByText('− R$ 89,90')).toBeInTheDocument()
    expect(screen.getByText(/1 movimentação pendente aparece abaixo e só entra no saldo depois de lançada/)).toBeInTheDocument()

    // Nada de saldo nem de fluxo realizado.
    expect(screen.queryByRole('region', { name: 'Saldo total' })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Fluxo do período' })).not.toBeInTheDocument()
  })
})

describe('HomeContent — NO_ACTIVITY_IN_PERIOD', () => {
  it('diz que não houve movimentação, mas preserva o saldo histórico', async () => {
    const { container } = render(<HomeContent overview={noActivityOverview()} />)

    expect(screen.getByRole('heading', { name: 'Nenhuma movimentação neste período' })).toBeInTheDocument()
    const balance = screen.getByRole('region', { name: 'Saldo total' })
    expect(within(balance).getByText('R$ 1.200,00')).toBeInTheDocument()
    expect(within(balance).getByText(/Saldo em 31 ago\./)).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Fluxo do período' })).not.toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Contas' })).toBeInTheDocument()
    expect(await axeViolations(container)).toEqual([])
  })

  it('zero real de um saldo é exibido como R$ 0,00 (≠ ausência)', () => {
    const overview = noActivityOverview()
    overview.summary.totalBalance = { amount: '0.00', currency: 'BRL' }
    render(<HomeContent overview={overview} />)
    expect(within(screen.getByRole('region', { name: 'Saldo total' })).getByText('R$ 0,00')).toBeInTheDocument()
  })
})
