import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { readyList, spikeDetail, spikeItem } from '@/features/insights/fixtures'
import { axeViolations } from '@/test/a11y'
import { json, stubBff } from '@/test/bff-stub'
import { CardDetailView } from './CardDetailView'
import { CardsView } from './CardsView'
import { EditCardView } from './EditCardView'
import { FutureInvoicesView } from './FutureInvoicesView'
import { InstallmentView } from './InstallmentView'
import { InvoiceView } from './InvoiceView'
import { NewCardView } from './NewCardView'
import {
  AURORA,
  auroraCard,
  auroraUtilization,
  future,
  history,
  invoice,
  items,
  NOTEBOOK,
  notebookPlan,
  summary,
} from './fixtures'

const push = vi.fn()

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push, replace: vi.fn() }),
  usePathname: () => '/cards',
  useSearchParams: () => new URLSearchParams(),
}))

const D11_ID = '0192b1f0-0000-7000-8000-0000000000d1'
const d11 = spikeItem({
  id: D11_ID,
  detector: 'INVOICE_ABOVE_PATTERN',
  type: 'CARDS',
  typeLabel: 'Cartões',
  title: 'Sua fatura do Aurora Platinum está 18% acima do seu padrão até agora, cerca de R$ 520 a mais.',
  support: 'R$ 3.412,80 até 22 set, contra média de R$ 2.892,20 nas últimas 3 faturas.',
  primaryAction: {
    kind: 'VIEW_INVOICE',
    label: 'Ver fatura',
    primary: true,
    available: true,
    params: { cardId: AURORA, dueMonth: '2026-10' },
  },
})
const brl = (amount: string) => ({ amount, currency: 'BRL' })

beforeEach(() => push.mockReset())
afterEach(() => vi.unstubAllGlobals())

describe('/cards — D-Cards', () => {
  it('métricas, bloco do cartão, insight de Cartões e parcelamentos com os valores do backend', async () => {
    stubBff({
      'GET /api/bff/cards/summary': () => json(summary()),
      'GET /api/bff/cards': () => json({ items: [auroraCard(), auroraCard({ id: 'arch', nickname: 'Antigo', status: 'ARCHIVED' })] }),
      'GET /api/bff/cards/': () => json({ items: [notebookPlan()] }),
      'GET /api/bff/insights': () => json(readyList({ items: [d11] })),
    })
    const { container } = render(<CardsView />)

    const metrics = await screen.findByRole('region', { name: 'Resumo dos cartões ativos' })
    expect(within(metrics).getByText(/18\.000,00/)).toBeInTheDocument()
    expect(within(metrics).getByText(/7\.793,20/)).toBeInTheDocument()
    expect(within(metrics).getByText(/4\.433,20/)).toBeInTheDocument()
    expect(screen.getByText(/não entra no saldo total/)).toHaveTextContent('regras invoice-v2')

    const block = screen.getByRole('article', { name: /Aurora Platinum •••• 4821 · Fatura de outubro/ })
    expect(within(block).getByText('Aberta')).toBeInTheDocument()
    expect(within(block).getByText(/pode mudar até o fechamento/)).toBeInTheDocument()
    expect(within(block).getByRole('img', { name: 'Utilização de 56% do limite' })).toBeInTheDocument()
    expect(within(block).getByText(/já comprometidos em parcelas/)).toHaveTextContent(/480,00/)
    expect(within(block).getByRole('link', { name: 'Ver fatura' })).toHaveAttribute('href', `/cards/${AURORA}/invoice`)
    // Nunca mais que os 4 últimos dígitos.
    expect(container.textContent).not.toMatch(/\d{5,}/)

    expect(await screen.findByText(/18% acima do seu padrão/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Entender' })).toHaveAttribute('href', `/insights/${D11_ID}`)
    expect(await screen.findByRole('link', { name: /Notebook · 10× de/ })).toHaveAttribute(
      'href',
      `/cards/${AURORA}/installments/${NOTEBOOK}`,
    )
    expect(await screen.findByRole('link', { name: 'Antigo •••• 4821' })).toHaveAttribute('href', '/cards/arch')
    expect(await axeViolations(container)).toEqual([])
  })

  it('primeira vez: sem cartões, convida a cadastrar', async () => {
    stubBff({
      'GET /api/bff/cards/summary': () => json({ ...summary(), cards: [] }),
      'GET /api/bff/cards': () => json({ items: [] }),
    })
    const { container } = render(<CardsView />)
    expect(await screen.findByRole('heading', { name: 'Acompanhe seus cartões aqui' })).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: 'Adicionar cartão' })[0]).toHaveAttribute('href', '/cards/new?from=cards')
    expect(await axeViolations(container)).toEqual([])
  })

  it('erro sem jargão com "Tentar novamente"; parcelamentos que falham são estado parcial', async () => {
    let fail = true
    stubBff({
      'GET /api/bff/cards/summary': () => (fail ? json({ code: 'UNAVAILABLE' }, 502) : json(summary())),
      'GET /api/bff/cards': () => json({ items: [auroraCard()] }),
      'GET /api/bff/cards/': () => json({ code: 'UNAVAILABLE' }, 502),
      'GET /api/bff/insights': () => json(readyList({ items: [] })),
    })
    const user = userEvent.setup()
    render(<CardsView />)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Não foi possível carregar seus cartões')
    expect(alert).not.toHaveTextContent('UNAVAILABLE')
    fail = false
    await user.click(screen.getByRole('button', { name: 'Tentar novamente' }))
    expect(await screen.findByText('Não conseguimos carregar os parcelamentos deste cartão.')).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Resumo dos cartões ativos' })).toBeInTheDocument()
  })
})

describe('/cards/:id — D-CardDetail', () => {
  const routes = () => ({
    'GET /api/bff/cards/': (_init: RequestInit | undefined, url: string) => {
      if (url.includes('/utilization/history')) return json(history())
      if (url.includes('/utilization')) return json(auroraUtilization())
      if (url.includes('/installments')) return json({ items: [notebookPlan()] })
      if (url.includes('/invoices')) return json({ items: [invoice().invoice, invoice({ dueMonth: '2026-09', status: 'PAID', partial: false }).invoice] })
      return json(auroraCard())
    },
  })

  it('utilização 56% (era 49%), composição, sem parcelas futuras, evolução, faturas e parcelamentos', async () => {
    stubBff(routes())
    const { container } = render(<CardDetailView id={AURORA} tab="overview" />)

    expect(await screen.findByRole('heading', { level: 1, name: 'Aurora Platinum' })).toBeInTheDocument()
    expect(screen.getByText(/Banco Aurora · final 4821 · fecha dia 28 · vence dia 05/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Visão geral' })).toHaveAttribute('aria-current', 'page')
    expect(await screen.findByText('56%')).toBeInTheDocument()
    expect(await screen.findByText('era 49% no fim de agosto')).toBeInTheDocument()
    expect(screen.getByText(/Sem as parcelas futuras, a utilização seria de 28%/)).toBeInTheDocument()
    expect(screen.getByText(/Parcelas futuras ·/)).toHaveTextContent(/3\.360,00/)
    expect(screen.getByRole('table', { name: 'Evolução da utilização' })).toHaveTextContent('56%')
    expect(await screen.findByRole('link', { name: 'Fatura de setembro de 2026' })).toHaveAttribute(
      'href',
      `/cards/${AURORA}/invoices/2026-09`,
    )
    expect(screen.getByText('Paga')).toBeInTheDocument()
    expect(await screen.findByText(/Parcela 3 de 10 na fatura atual/)).toBeInTheDocument()
    // Sem média de faturas nem "fonte" do cartão (ADR-0029 §8).
    expect(screen.queryByText(/Média de/)).not.toBeInTheDocument()
    expect(await axeViolations(container)).toEqual([])
  })

  it('cartão inexistente (ou de outro Workspace) e id inválido mostram "não encontrado"', async () => {
    stubBff({ 'GET /api/bff/cards/': () => json({ code: 'CARD_NOT_FOUND' }, 404) })
    render(<CardDetailView id={AURORA} tab="overview" />)
    expect(await screen.findByRole('heading', { name: 'Cartão não encontrado' })).toBeInTheDocument()
  })
})

describe('Fatura — D-Invoice', () => {
  const routes = (extra: Record<string, (init: RequestInit | undefined, url: string) => Response | Promise<Response>> = {}) => ({
    'GET /api/bff/categories': () => json({ items: [{ code: 'DINING_OUT', name: 'Alimentação fora', nature: 'EXPENSE', active: true }] }),
    'GET /api/bff/insights': () => json(readyList({ items: [d11] })),
    [`GET /api/bff/insights/${D11_ID}`]: () =>
      json(
        spikeDetail({
          insight: d11,
          figures: [
            { code: 'current', label: 'Fatura até agora', kind: 'MONEY', value: '3412.80', from: '2026-08-28', to: '2026-09-22' },
            { code: 'average', label: 'Média das 3 faturas anteriores', kind: 'MONEY', value: '2892.20', from: '2026-05-28', to: '2026-08-27' },
          ],
          contributors: [
            { kind: 'FACTOR', key: 'category:DINING_OUT', name: 'Alimentação fora', amount: brl('311.00'), count: null, share: null, day: null },
            { kind: 'FACTOR', key: 'plan:x', name: 'Parcela de Loja Tech (3/10)', amount: brl('160.00'), count: null, share: null, day: null },
            { kind: 'FACTOR', key: 'category:TRANSPORT', name: 'Transporte', amount: brl('49.60'), count: null, share: null, day: null },
          ],
        }),
      ),
    'GET /api/bff/cards/': (_init: RequestInit | undefined, url: string) => {
      if (url.includes('/items')) return json(items())
      if (url.includes('/invoices/future')) return json(future())
      if (url.includes('/invoices/')) return json(invoice())
      return json(auroraCard())
    },
    ...extra,
  })

  it('fatura atual: valor parcial, datas, mínimo só do arquivo, explicação do D11, lançamentos e próxima fatura sem estimativa', async () => {
    const calls = stubBff(routes())
    const { container } = render(<InvoiceView cardId={AURORA} dueMonth={null} />)

    expect(await screen.findByRole('heading', { level: 1, name: 'Fatura de outubro' })).toBeInTheDocument()
    expect(screen.getByText('Valor até agora')).toBeInTheDocument()
    expect(screen.getByText(/pode mudar até o fechamento em 28 set/)).toBeInTheDocument()
    expect(screen.getByText(/Pagamento mínimo: só quando informado no arquivo/)).toBeInTheDocument()
    expect(screen.getByText(/regras invoice-v2/)).toBeInTheDocument()
    // Início do acompanhamento (texto curto aprovado, ADR-0029 §4).
    expect(screen.getByRole('note')).toHaveTextContent('Primeira fatura acompanhada pelo app.')
    expect(screen.getByRole('link', { name: 'Registrar pagamento' })).toHaveAttribute(
      'href',
      `/transactions/new?type=CARD_PAYMENT&cardId=${AURORA}`,
    )

    const factors = await screen.findByRole('list', { name: 'Fatores da diferença' })
    expect(within(factors).getAllByRole('listitem')).toHaveLength(3)
    expect(screen.getByText('Confiança alta. Comparação com as suas faturas anteriores, sem IA.')).toBeInTheDocument()
    expect(screen.getByText('Perguntar ao Copilot · Em breve')).toBeInTheDocument()

    const notebook = await screen.findByRole('link', { name: 'Loja Tech' })
    expect(notebook).toHaveAttribute('href', `/cards/${AURORA}/installments/${NOTEBOOK}`)
    expect(screen.getByText(/Parcela 3 de 10/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Bistrô Lume' })).toHaveAttribute('href', '/transactions/01922f5e-0000-7000-8000-0000000000b2')
    expect(screen.getByText('Mostrando 2 de 2 lançamentos')).toBeInTheDocument()
    expect(calls.some((c) => c.url === `/api/bff/cards/${AURORA}/invoices/2026-10/items?page=0&pageSize=20`)).toBe(true)

    expect(await screen.findByText('Já comprometido (parcelas)')).toBeInTheDocument()
    expect(screen.queryByText(/[Ee]stimativa|[Ee]stimad/)).not.toBeInTheDocument()
    expect(await axeViolations(container)).toEqual([])
  })

  it('sem D11 para a fatura, sem explicação; fatura passada sem lançamentos é "não encontrada"', async () => {
    stubBff(routes({ 'GET /api/bff/insights': () => json(readyList({ items: [] })) }))
    render(<InvoiceView cardId={AURORA} dueMonth="2026-10" />)
    expect(await screen.findByRole('heading', { level: 1, name: 'Fatura de outubro' })).toBeInTheDocument()
    await screen.findByText('Mostrando 2 de 2 lançamentos')
    expect(screen.queryByRole('list', { name: 'Fatores da diferença' })).not.toBeInTheDocument()
    vi.unstubAllGlobals()

    stubBff(routes({ 'GET /api/bff/cards/': (_i, url) => (url.includes('/invoices/') ? json({ code: 'INVOICE_NOT_FOUND' }, 404) : json(auroraCard())) }))
    render(<InvoiceView cardId={AURORA} dueMonth="2026-01" />)
    expect(await screen.findByRole('heading', { name: 'Fatura não encontrada' })).toBeInTheDocument()
  })
})

describe('Próximas faturas — D-FutureInvoices', () => {
  it('só o comprometido, com o aviso de que novas compras não aparecem', async () => {
    stubBff({
      'GET /api/bff/cards/': (_init: RequestInit | undefined, url: string) => (url.includes('/future') ? json(future()) : json(auroraCard())),
    })
    const { container } = render(<FutureInvoicesView cardId={AURORA} />)
    expect(await screen.findByRole('heading', { level: 1, name: 'Próximas faturas' })).toBeInTheDocument()
    expect(await screen.findByText('Novas compras ainda não aparecem aqui.')).toBeInTheDocument()
    expect(screen.getByText(/Total comprometido/)).toHaveTextContent(/960,00 em 2 faturas/)
    expect(screen.getByText('Notebook · parcela 4/10')).toBeInTheDocument()
    expect(screen.queryByText(/≈/)).not.toBeInTheDocument()
    expect(await axeViolations(container)).toEqual([])
  })

  it('sem parcelas futuras: estado vazio explicado', async () => {
    stubBff({
      'GET /api/bff/cards/': (_init: RequestInit | undefined, url: string) =>
        url.includes('/future') ? json({ ...future(), months: [], totalCommitted: brl('0.00') }) : json(auroraCard()),
    })
    render(<FutureInvoicesView cardId={AURORA} />)
    expect(await screen.findByRole('heading', { name: 'Nenhuma parcela comprometida' })).toBeInTheDocument()
  })
})

describe('Parcelamento — D-Installment', () => {
  it('blocos com "Antes do acompanhamento", impacto do backend (14% e 28%) e link para a saúde', async () => {
    stubBff({
      'GET /api/bff/cards/': (_init: RequestInit | undefined, url: string) => {
        if (url.includes('/installments/')) return json(notebookPlan())
        if (url.includes('/utilization')) return json(auroraUtilization())
        return json(auroraCard())
      },
    })
    const { container } = render(<InstallmentView cardId={AURORA} transactionId={NOTEBOOK} />)

    expect(await screen.findByRole('heading', { level: 1, name: /Notebook · 10× de/ })).toBeInTheDocument()
    const blocks = screen.getByRole('list', { name: 'Parcelas' })
    expect(within(blocks).getAllByRole('listitem')).toHaveLength(10)
    expect(within(blocks).getAllByText('Antes do acompanhamento')).toHaveLength(2)
    expect(within(blocks).getByText('Nesta fatura')).toBeInTheDocument()
    expect(screen.getByText('14%')).toBeInTheDocument()
    expect(screen.getByText(/da fatura de outubro/)).toHaveTextContent(/480,00 de R\$\s3\.412,80/)
    expect(await screen.findByText('28%')).toBeInTheDocument()
    expect(screen.queryByText(/renda/)).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ver saúde financeira' })).toHaveAttribute('href', '/home/health')
    expect(screen.getByRole('link', { name: 'Ver fatura' })).toHaveAttribute('href', `/cards/${AURORA}/invoices/2026-10`)
    expect(await axeViolations(container)).toEqual([])
  })

  it('compra de outro cartão ou Workspace é "não encontrado"', async () => {
    stubBff({ 'GET /api/bff/cards/': (_i, url: string) => (url.includes('/installments/') ? json({ code: 'TRANSACTION_NOT_FOUND' }, 404) : json(auroraCard())) })
    render(<InstallmentView cardId={AURORA} transactionId={NOTEBOOK} />)
    expect(await screen.findByRole('heading', { name: 'Parcelamento não encontrado' })).toBeInTheDocument()
  })
})

describe('Editar, arquivar e reativar (ADR-0029 §7)', () => {
  const accounts = { items: [] }

  it('edição pré-preenchida envia o PUT; arquivar pede confirmação', async () => {
    let current = auroraCard()
    const calls = stubBff({
      'GET /api/bff/accounts': () => json(accounts),
      'GET /api/bff/cards/': () => json(current),
      'PUT /api/bff/cards/': (init) => {
        current = { ...current, ...(JSON.parse(String(init?.body)) as object), limit: brl('15000.00') }
        return json(current)
      },
      'POST /api/bff/cards/': () => json({ ...current, status: 'ARCHIVED' }),
    })
    const user = userEvent.setup()
    const { container } = render(<EditCardView id={AURORA} />)

    const limit = await screen.findByLabelText('Limite')
    expect(limit).toHaveValue('12000,00')
    expect(screen.getByLabelText('4 últimos dígitos')).toHaveValue('4821')
    await user.clear(limit)
    await user.type(limit, '15.000,00')
    await user.click(screen.getByRole('button', { name: 'Salvar alterações' }))
    expect(await screen.findByText(/Alterações salvas/)).toBeInTheDocument()
    const put = calls.find((c) => c.method === 'PUT')!
    expect(put.url).toBe(`/api/bff/cards/${AURORA}`)
    expect(JSON.parse(String(put.init?.body))).toMatchObject({ limit: '15000.00', last4: '4821', closingDay: 28, dueDay: 5 })
    expect(await axeViolations(container)).toEqual([])

    await user.click(screen.getByRole('button', { name: 'Arquivar cartão' }))
    expect(calls.some((c) => c.method === 'POST')).toBe(false)
    await user.click(screen.getByRole('button', { name: 'Confirmar arquivamento' }))
    expect(await screen.findByText(/Cartão arquivado\. O histórico foi mantido/)).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'POST')!.url).toBe(`/api/bff/cards/${AURORA}/archive`)
    expect(screen.getByRole('button', { name: 'Reativar cartão' })).toBeInTheDocument()
  })

  it('conflito de versão explica e oferece recarregar', async () => {
    stubBff({
      'GET /api/bff/accounts': () => json(accounts),
      'GET /api/bff/cards/': () => json(auroraCard()),
      'PUT /api/bff/cards/': () => json({ code: 'CONFLICT' }, 409),
    })
    const user = userEvent.setup()
    render(<EditCardView id={AURORA} />)
    await user.click(await screen.findByRole('button', { name: 'Salvar alterações' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('O cartão foi alterado em outro lugar')
    expect(screen.getByRole('button', { name: 'Recarregar dados' })).toBeInTheDocument()
  })

  it('vindo de /cards, o cadastro leva ao cartão criado', async () => {
    stubBff({
      'GET /api/bff/accounts': () => json(accounts),
      'POST /api/bff/cards': () => json(auroraCard(), 201),
    })
    const user = userEvent.setup()
    render(<NewCardView from="cards" />)
    await user.type(await screen.findByLabelText('Apelido'), 'Aurora Platinum')
    await user.selectOptions(screen.getByLabelText('Bandeira'), 'MASTERCARD')
    await user.type(screen.getByLabelText('4 últimos dígitos'), '4821')
    await user.type(screen.getByLabelText('Limite'), '12.000,00')
    await user.type(screen.getByLabelText('Dia de fechamento'), '28')
    await user.type(screen.getByLabelText('Dia de vencimento'), '5')
    await user.click(screen.getByRole('button', { name: 'Cadastrar cartão' }))
    await waitFor(() => expect(push).toHaveBeenCalledWith(`/cards/${AURORA}`))
    expect(screen.getByRole('link', { name: 'Cartões' })).toHaveAttribute('href', '/cards')
  })
})
