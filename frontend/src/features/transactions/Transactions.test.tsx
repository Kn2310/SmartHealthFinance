import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { NewAccountView } from '@/features/accounts/NewAccountView'
import { AccountDetailView } from '@/features/accounts/AccountDetailView'
import { readyOverview } from '@/features/home/fixtures'
import { axeViolations } from '@/test/a11y'
import { json, stubBff } from '@/test/bff-stub'
import type { Account, Transaction } from './api'
import { MoreView } from './MoreView'
import { NewTransactionView } from './NewTransactionView'
import { TransactionDetailView } from './TransactionDetailView'
import { TransactionsView } from './TransactionsView'

const push = vi.fn()
const replace = vi.fn()
let search = new URLSearchParams()

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push, replace }),
  usePathname: () => '/transactions',
  useSearchParams: () => search,
}))

const AURORA = '01922f5e-0000-7000-8000-0000000000a1'
const NORTE = '01922f5e-0000-7000-8000-0000000000a2'
const TXN = '01922f5e-0000-7000-8000-0000000000b1'

const account = (overrides: Partial<Account> = {}): Account => ({
  id: AURORA,
  workspaceId: 'ws',
  name: 'Conta Aurora',
  type: 'CHECKING',
  institutionName: 'Banco Aurora',
  currency: 'BRL',
  includedInTotal: true,
  status: 'ACTIVE',
  createdAt: '2026-09-01T00:00:00Z',
  updatedAt: '2026-09-01T00:00:00Z',
  ...overrides,
})

const txn = (overrides: Partial<Transaction> = {}): Transaction => ({
  id: TXN,
  workspaceId: 'ws',
  accountId: AURORA,
  destinationAccountId: null,
  type: 'EXPENSE',
  adjustmentDirection: null,
  amount: { amount: '86.40', currency: 'BRL' },
  occurredOn: '2026-10-08',
  description: 'Bistrô Lume',
  status: 'POSTED',
  source: 'MANUAL',
  refundOfTransactionId: null,
  createdAt: '2026-10-08T12:00:00Z',
  updatedAt: '2026-10-08T12:00:00Z',
  ...overrides,
})

const page = (items: Transaction[], totalItems = items.length, pageNumber = 0) => ({ items, page: pageNumber, pageSize: 50, totalItems })
const accountsList = (items: Account[] = [account(), account({ id: NORTE, name: 'Reserva', type: 'SAVINGS' })]) => ({ items })
const body = (init: RequestInit | undefined) => JSON.parse(String(init?.body)) as Record<string, unknown>
const header = (init: RequestInit | undefined, name: string) => (init?.headers as Record<string, string>)[name]

beforeEach(() => {
  push.mockReset()
  replace.mockReset()
  search = new URLSearchParams()
  // 9/10/2026 meio-dia em Brasília: "hoje" e "este mês" previsíveis.
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-10-09T15:00:00Z'))
})
afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('/transactions — lista', () => {
  it('lista o mês corrente agrupada por dia, com sinal, status e origem; acessível', async () => {
    const calls = stubBff({
      'GET /api/bff/transactions': () =>
        json(
          page([
            txn({ id: 'a', description: 'Salário', type: 'INCOME', amount: { amount: '6500.00', currency: 'BRL' }, occurredOn: '2026-10-09' }),
            txn({ id: 'b', description: 'Mercado', status: 'PENDING', source: 'IMPORT', occurredOn: '2026-10-09' }),
            txn({ id: 'c', description: 'Para a reserva', type: 'TRANSFER', destinationAccountId: NORTE }),
          ]),
        ),
      'GET /api/bff/accounts': () => json(accountsList()),
    })
    const { container } = render(<TransactionsView />)

    expect(screen.getByRole('heading', { level: 1, name: 'Transações' })).toBeInTheDocument()
    expect(screen.getByText('Carregando transações…')).toBeInTheDocument()
    expect(await screen.findByText('Salário')).toBeInTheDocument()

    const list = calls.find((c) => c.url.startsWith('/api/bff/transactions'))!
    expect(list.url).toBe('/api/bff/transactions?from=2026-10-01&to=2026-10-31')
    expect(screen.getByText('1 a 31 de outubro · 3 transações')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 3, name: '9 out · sexta' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 3, name: '8 out · quinta' })).toBeInTheDocument()

    const salary = screen.getByRole('link', { name: /Salário/ })
    expect(salary).toHaveAttribute('href', '/transactions/a')
    expect(within(salary).getByText('mais R$ 6.500,00')).toBeInTheDocument()
    const market = screen.getByRole('link', { name: /Mercado/ })
    expect(within(market).getByText('Pendente')).toBeInTheDocument()
    expect(within(market).getByText('Importado')).toBeInTheDocument()
    // Transferência na lista geral: sem sinal (não afirma direção), com origem → destino.
    const transfer = screen.getByRole('link', { name: /Para a reserva/ })
    expect(within(transfer).getByText('Transferência · Conta Aurora → Reserva')).toBeInTheDocument()
    expect(within(transfer).getByText('R$ 86,40')).toBeInTheDocument()

    expect(screen.getByRole('link', { name: 'Nova transação' })).toHaveAttribute('href', '/transactions/new')
    expect(await axeViolations(container)).toEqual([])
  })

  it('filtros vão para a URL (sem a busca por texto) e voltam para a lista', async () => {
    stubBff({ 'GET /api/bff/transactions': () => json(page([txn()])), 'GET /api/bff/accounts': () => json(accountsList()) })
    const user = userEvent.setup()
    render(<TransactionsView />)
    await screen.findByText('Bistrô Lume')

    await user.selectOptions(screen.getByLabelText('Tipo'), 'EXPENSE')
    expect(replace).toHaveBeenLastCalledWith('/transactions?type=EXPENSE', { scroll: false })
    await user.selectOptions(screen.getByLabelText('Status'), 'PENDING')
    await user.selectOptions(screen.getByLabelText('Conta'), NORTE)
    await user.selectOptions(screen.getByLabelText('Período'), 'PREVIOUS_MONTH')
    expect(replace).toHaveBeenLastCalledWith('/transactions?period=PREVIOUS_MONTH', { scroll: false })
  })

  it('lê os filtros da URL e envia ao BFF com o período convertido; a busca vai só na chamada', async () => {
    search = new URLSearchParams(`period=PREVIOUS_MONTH&type=INCOME&status=POSTED&accountId=${NORTE}&workspaceId=x`)
    const calls = stubBff({ 'GET /api/bff/transactions': () => json(page([txn()])), 'GET /api/bff/accounts': () => json(accountsList()) })
    const user = userEvent.setup()
    render(<TransactionsView />)
    await screen.findByText('Bistrô Lume')
    expect(calls.find((c) => c.url.startsWith('/api/bff/transactions'))!.url).toBe(
      `/api/bff/transactions?from=2026-09-01&to=2026-09-30&accountId=${NORTE}&type=INCOME&status=POSTED`,
    )
    expect(screen.getByRole('link', { name: 'Nova transação' })).toHaveAttribute('href', `/transactions/new?accountId=${NORTE}`)

    await user.type(screen.getByRole('searchbox', { name: 'Buscar na descrição' }), 'bistrô')
    await user.click(screen.getByRole('button', { name: 'Buscar' }))
    await waitFor(() => expect(calls.at(-1)!.url).toContain('q=bistr%C3%B4'))
    expect(replace).not.toHaveBeenCalled()
  })

  it('período personalizado: datas inválidas não são aplicadas', async () => {
    search = new URLSearchParams('period=CUSTOM&from=2026-10-01&to=2026-10-05')
    stubBff({ 'GET /api/bff/transactions': () => json(page([txn()])), 'GET /api/bff/accounts': () => json(accountsList()) })
    const user = userEvent.setup()
    render(<TransactionsView />)
    await screen.findByText('Bistrô Lume')

    const to = screen.getByLabelText('Até')
    await user.clear(to)
    await user.type(to, '2026-09-01')
    expect(screen.getByRole('button', { name: 'Aplicar período' })).toBeDisabled()
    expect(to).toHaveAccessibleDescription(/final igual ou depois da inicial/)
  })

  it('primeira vez (sem transações e sem filtros): convida a registrar ou importar', async () => {
    stubBff({ 'GET /api/bff/transactions': () => json(page([])), 'GET /api/bff/accounts': () => json(accountsList()) })
    const { container } = render(<TransactionsView />)
    expect(await screen.findByRole('heading', { name: 'Nenhuma transação neste mês' })).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: 'Nova transação' })).toHaveLength(2)
    expect(await axeViolations(container)).toEqual([])
  })

  it('filtros sem resultado: "nenhuma encontrada" e limpar filtros volta ao padrão', async () => {
    search = new URLSearchParams('type=REFUND')
    stubBff({ 'GET /api/bff/transactions': () => json(page([])), 'GET /api/bff/accounts': () => json(accountsList()) })
    const user = userEvent.setup()
    render(<TransactionsView />)
    expect(await screen.findByRole('heading', { name: 'Nenhuma transação encontrada' })).toBeInTheDocument()
    await user.click(screen.getAllByRole('button', { name: 'Limpar filtros' })[0]!)
    expect(replace).toHaveBeenLastCalledWith('/transactions', { scroll: false })
  })

  it('erro: mensagem neutra e tentar novamente refaz a chamada', async () => {
    let fail = true
    const calls = stubBff({
      'GET /api/bff/transactions': () => (fail ? json({ code: 'UNAVAILABLE' }, 502) : json(page([txn()]))),
      'GET /api/bff/accounts': () => json(accountsList()),
    })
    const user = userEvent.setup()
    const { container } = render(<TransactionsView />)
    expect(await screen.findByRole('heading', { name: 'Não foi possível carregar suas transações' })).toBeInTheDocument()
    expect(await axeViolations(container)).toEqual([])
    fail = false
    await user.click(screen.getByRole('button', { name: 'Tentar novamente' }))
    expect(await screen.findByText('Bistrô Lume')).toBeInTheDocument()
    expect(calls.filter((c) => c.url.startsWith('/api/bff/transactions'))).toHaveLength(2)
  })

  it('carregar mais pede a próxima página e acrescenta sem repetir', async () => {
    const first = Array.from({ length: 50 }, (_, i) => txn({ id: `t${i}`, description: `Compra ${i}` }))
    const calls = stubBff({
      'GET /api/bff/transactions': (_init, url) =>
        url.includes('page=1') ? json(page([first[49]!, txn({ id: 'last', description: 'Última' })], 51, 1)) : json(page(first, 51)),
      'GET /api/bff/accounts': () => json(accountsList()),
    })
    const user = userEvent.setup()
    render(<TransactionsView />)
    await screen.findByText('Compra 0')
    await user.click(screen.getByRole('button', { name: 'Carregar mais' }))
    expect(await screen.findByText('Última')).toBeInTheDocument()
    expect(calls.at(-1)!.url).toBe('/api/bff/transactions?from=2026-10-01&to=2026-10-31&page=1')
    expect(screen.getAllByText('Compra 49')).toHaveLength(1)
    expect(screen.queryByRole('button', { name: 'Carregar mais' })).not.toBeInTheDocument()
  })

  it('aviso depois de registrar (?registered=posted)', async () => {
    search = new URLSearchParams('registered=posted')
    stubBff({ 'GET /api/bff/transactions': () => json(page([txn()])), 'GET /api/bff/accounts': () => json(accountsList()) })
    render(<TransactionsView />)
    expect(screen.getByText(/Transação registrada\. O saldo da conta e da Home/)).toBeInTheDocument()
  })
})

describe('/transactions/new — lançamento manual', () => {
  const routes = (create: (init: RequestInit | undefined) => Response) => ({
    'GET /api/bff/accounts': () => json(accountsList([account(), account({ id: NORTE, name: 'Reserva' }), account({ id: 'x', name: 'Antiga', status: 'ARCHIVED' })])),
    'POST /api/bff/transactions': create,
  })

  async function fill(user: ReturnType<typeof userEvent.setup>, { type = 'Despesa', amount = '1.234,56', description = 'Mercado' } = {}) {
    await user.click(await screen.findByRole('radio', { name: type }))
    await user.selectOptions(screen.getByLabelText('Conta'), AURORA)
    await user.type(screen.getByLabelText('Valor'), amount)
    await user.type(screen.getByLabelText('Descrição'), description)
  }

  it('registra despesa com valor em string decimal, data de hoje e POSTED, e volta para a lista', async () => {
    const calls = stubBff(routes(() => json(txn(), 201)))
    const user = userEvent.setup()
    const { container } = render(<NewTransactionView initialAccountId={null} returnTo={{ kind: 'transactions' }} />)

    expect(await screen.findByLabelText('Data')).toHaveValue('2026-10-09')
    // Só contas ativas podem receber lançamento.
    expect(within(screen.getByLabelText('Conta')).queryByRole('option', { name: 'Antiga' })).not.toBeInTheDocument()
    expect(await axeViolations(container)).toEqual([])

    await fill(user)
    await user.click(screen.getByRole('button', { name: 'Registrar transação' }))

    await waitFor(() => expect(push).toHaveBeenCalledWith('/transactions?registered=posted'))
    const post = calls.find((c) => c.method === 'POST')!
    expect(body(post.init)).toEqual({
      type: 'EXPENSE',
      accountId: AURORA,
      adjustmentDirection: null,
      amount: '1234.56',
      occurredOn: '2026-10-09',
      description: 'Mercado',
      status: 'POSTED',
    })
    expect(header(post.init, 'Idempotency-Key')).toMatch(/^web:[0-9a-f-]{36}$/)
    expect(screen.getByRole('button', { name: 'Registrando…' })).toBeDisabled()
  })

  it('duplo clique envia UMA vez; reenvio depois de falha de rede usa a MESMA Idempotency-Key', async () => {
    let attempt = 0
    // O primeiro envio fica pendurado até o teste decidir: o segundo clique acontece com ele em andamento.
    let failFirst: (response: Response) => void = () => {}
    const firstResponse = new Promise<Response>((resolve) => (failFirst = resolve))
    const calls = stubBff({
      ...routes(() => json(txn(), 200)),
      'POST /api/bff/transactions': () => (++attempt === 1 ? firstResponse : json(txn(), 200)),
    })
    const user = userEvent.setup()
    render(<NewTransactionView initialAccountId={AURORA} returnTo={{ kind: 'account', accountId: AURORA }} />)
    await fill(user, { type: 'Receita' })

    await user.dblClick(screen.getByRole('button', { name: 'Registrar transação' }))
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(1)
    failFirst(json({ code: 'UNAVAILABLE' }, 502))
    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível falar com o servidor')
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(1)

    await user.click(screen.getByRole('button', { name: 'Registrar transação' }))
    await waitFor(() => expect(push).toHaveBeenCalledWith(`/accounts/${AURORA}?registered=posted`))
    const posts = calls.filter((c) => c.method === 'POST')
    expect(posts).toHaveLength(2)
    expect(header(posts[1]!.init, 'Idempotency-Key')).toBe(header(posts[0]!.init, 'Idempotency-Key'))
  })

  it('IDEMPOTENCY_KEY_REUSED: avisa e o próximo envio é uma nova intenção (outra chave)', async () => {
    let attempt = 0
    const calls = stubBff(routes(() => (++attempt === 1 ? json({ code: 'IDEMPOTENCY_KEY_REUSED' }, 422) : json(txn(), 201))))
    const user = userEvent.setup()
    render(<NewTransactionView initialAccountId={null} returnTo={{ kind: 'transactions' }} />)
    await fill(user)
    await user.click(screen.getByRole('button', { name: 'Registrar transação' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('já foi registrado')

    await user.click(screen.getByRole('button', { name: 'Registrar transação' }))
    await waitFor(() => expect(push).toHaveBeenCalled())
    const posts = calls.filter((c) => c.method === 'POST')
    expect(header(posts[1]!.init, 'Idempotency-Key')).not.toBe(header(posts[0]!.init, 'Idempotency-Key'))
  })

  it('validação local: valor inválido e ajuste sem direção, com foco no primeiro erro, sem chamar o BFF', async () => {
    const calls = stubBff(routes(() => json(txn(), 201)))
    const user = userEvent.setup()
    const { container } = render(<NewTransactionView initialAccountId={AURORA} returnTo={{ kind: 'transactions' }} />)
    await user.click(await screen.findByRole('radio', { name: 'Ajuste' }))
    await user.type(screen.getByLabelText('Valor'), '10,999')
    await user.click(screen.getByRole('button', { name: 'Registrar transação' }))

    await waitFor(() => expect(screen.getByRole('radio', { name: 'Aumenta o saldo' })).toHaveFocus())
    expect(screen.getByText('Escolha se o ajuste aumenta ou diminui o saldo.')).toBeInTheDocument()
    expect(screen.getByLabelText('Valor')).toHaveAccessibleDescription(/no máximo 2 casas decimais/)
    expect(screen.getByLabelText('Descrição')).toHaveAccessibleDescription(/Descreva a transação/)
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0)
    expect(await axeViolations(container)).toEqual([])
  })

  it('ajuste com direção e pendente; conta arquivada no servidor vira erro no campo Conta', async () => {
    const calls = stubBff(routes(() => json({ code: 'ACCOUNT_ARCHIVED' }, 409)))
    const user = userEvent.setup()
    render(<NewTransactionView initialAccountId={AURORA} returnTo={{ kind: 'transactions' }} />)
    await fill(user, { type: 'Ajuste', amount: '200' })
    await user.click(screen.getByRole('radio', { name: 'Diminui o saldo' }))
    await user.click(screen.getByRole('checkbox', { name: 'Ainda pendente' }))
    await user.click(screen.getByRole('button', { name: 'Registrar transação' }))

    await waitFor(() => expect(screen.getByLabelText('Conta')).toHaveFocus())
    expect(screen.getByLabelText('Conta')).toHaveAccessibleDescription(/arquivada/)
    expect(body(calls.find((c) => c.method === 'POST')!.init)).toMatchObject({
      type: 'ADJUSTMENT',
      adjustmentDirection: 'DECREASE',
      amount: '200.00',
      status: 'PENDING',
    })
  })

  it('sem conta ativa: pede para criar uma conta antes', async () => {
    stubBff({ 'GET /api/bff/accounts': () => json(accountsList([account({ status: 'ARCHIVED' })])) })
    render(<NewTransactionView initialAccountId={null} returnTo={{ kind: 'transactions' }} />)
    expect(await screen.findByRole('heading', { name: 'Crie uma conta primeiro' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Adicionar conta' })).toHaveAttribute('href', '/accounts/new')
  })
})

describe('/transactions/{id} — detalhe e ações', () => {
  const accounts = { 'GET /api/bff/accounts': () => json(accountsList()) }

  it('pendente: mostra dados e efetiva (POSTED) com aviso de saldo', async () => {
    const calls = stubBff({
      ...accounts,
      'GET /api/bff/transactions/': () => json(txn({ status: 'PENDING' })),
      'POST /api/bff/transactions/': () => json(txn({ status: 'POSTED' })),
    })
    const user = userEvent.setup()
    const { container } = render(<TransactionDetailView id={TXN} />)

    expect(await screen.findByRole('heading', { level: 1, name: 'Bistrô Lume' })).toBeInTheDocument()
    expect(screen.getByText('menos R$ 86,40')).toBeInTheDocument()
    expect(screen.getByText(/Ainda não entra no saldo/)).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: 'Conta Aurora' })).toHaveAttribute('href', `/accounts/${AURORA}`)
    expect(screen.queryByRole('button', { name: 'Estornar' })).not.toBeInTheDocument()
    expect(await axeViolations(container)).toEqual([])

    await user.click(screen.getByRole('button', { name: 'Efetivar' }))
    expect(await screen.findByText(/Transação efetivada/)).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'POST')!.url).toBe(`/api/bff/transactions/${TXN}/post`)
    expect(screen.getByRole('button', { name: 'Estornar' })).toBeInTheDocument()
  })

  it('lançada: estornar pede confirmação; voltar não chama o BFF', async () => {
    const calls = stubBff({
      ...accounts,
      'GET /api/bff/transactions/': () => json(txn()),
      'POST /api/bff/transactions/': () => json(txn({ status: 'REVERSED' })),
    })
    const user = userEvent.setup()
    const { container } = render(<TransactionDetailView id={TXN} />)

    await user.click(await screen.findByRole('button', { name: 'Estornar' }))
    expect(screen.getByRole('group')).toHaveTextContent('não pode ser desfeito')
    expect(await axeViolations(container)).toEqual([])
    await user.click(screen.getByRole('button', { name: 'Voltar' }))
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0)

    await user.click(screen.getByRole('button', { name: 'Estornar' }))
    await user.click(screen.getByRole('button', { name: 'Confirmar estorno' }))
    expect(await screen.findByText(/Transação estornada/)).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'POST')!.url).toBe(`/api/bff/transactions/${TXN}/reverse`)
    expect(screen.getAllByText('Estornada').length).toBeGreaterThan(0)
    expect(screen.queryByRole('button', { name: 'Estornar' })).not.toBeInTheDocument()
  })

  it('pendente: cancelar com confirmação; conflito de status explica e oferece recarregar', async () => {
    stubBff({
      ...accounts,
      'GET /api/bff/transactions/': () => json(txn({ status: 'PENDING' })),
      'POST /api/bff/transactions/': () => json({ code: 'TRANSACTION_STATUS_CONFLICT' }, 409),
    })
    const user = userEvent.setup()
    render(<TransactionDetailView id={TXN} />)
    await user.click(await screen.findByRole('button', { name: 'Cancelar transação' }))
    await user.click(screen.getByRole('button', { name: 'Confirmar cancelamento' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('O status desta transação mudou')
    expect(screen.getByRole('button', { name: 'Recarregar dados' })).toBeInTheDocument()
  })

  it('edita só a descrição', async () => {
    const calls = stubBff({
      ...accounts,
      'GET /api/bff/transactions/': () => json(txn()),
      'PUT /api/bff/transactions/': (init) => json(txn({ description: String(body(init).description) })),
    })
    const user = userEvent.setup()
    render(<TransactionDetailView id={TXN} />)
    const input = await screen.findByRole('textbox', { name: 'Descrição' })
    await user.clear(input)
    await user.type(input, '  Jantar  ')
    await user.click(screen.getByRole('button', { name: 'Salvar descrição' }))
    expect(await screen.findByText('Descrição atualizada.')).toBeInTheDocument()
    expect(body(calls.find((c) => c.method === 'PUT')!.init)).toEqual({ description: 'Jantar' })
    expect(screen.getByRole('heading', { level: 1, name: 'Jantar' })).toBeInTheDocument()
  })

  it('cancelada/estornada: sem ações e valor sem sinal', async () => {
    stubBff({ ...accounts, 'GET /api/bff/transactions/': () => json(txn({ status: 'CANCELLED' })) })
    render(<TransactionDetailView id={TXN} />)
    expect(await screen.findByText(/Não afeta o saldo/)).toBeInTheDocument()
    expect(screen.getByText('R$ 86,40')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Efetivar|Estornar|Cancelar transação/ })).not.toBeInTheDocument()
  })

  it('de outro Workspace ou inexistente: "não encontrada"; id inválido nem chega ao BFF', async () => {
    stubBff({ 'GET /api/bff/transactions/': () => json({ code: 'TRANSACTION_NOT_FOUND' }, 404) })
    const { unmount } = render(<TransactionDetailView id={TXN} />)
    expect(await screen.findByRole('heading', { name: 'Transação não encontrada' })).toBeInTheDocument()
    unmount()
    const calls = stubBff({})
    render(<TransactionDetailView id={null} />)
    expect(screen.getByRole('heading', { name: 'Transação não encontrada' })).toBeInTheDocument()
    expect(calls).toHaveLength(0)
  })
})

describe('/accounts/{id} — movimentações da conta', () => {
  it('lista só a conta (todo o período), transferência recebida com sinal de entrada, sem "Em breve"', async () => {
    const calls = stubBff({
      'GET /api/bff/accounts/': () => json(account({ id: NORTE, name: 'Reserva' })),
      'GET /api/bff/accounts': () => json(accountsList()),
      'GET /api/bff/overview': () => json(readyOverview()),
      'GET /api/bff/transactions': () =>
        json(page([txn({ description: 'Para a reserva', type: 'TRANSFER', destinationAccountId: NORTE, amount: { amount: '500.00', currency: 'BRL' } })])),
    })
    const { container } = render(<AccountDetailView id={NORTE} registered="pending" />)

    const section = await screen.findByRole('region', { name: /Movimentações/ })
    const row = await within(section).findByRole('link', { name: /Para a reserva/ })
    expect(within(row).getByText('mais R$ 500,00')).toBeInTheDocument()
    expect(calls.find((c) => c.url.startsWith('/api/bff/transactions'))!.url).toBe(`/api/bff/transactions?accountId=${NORTE}`)
    expect(within(section).queryByLabelText('Conta')).not.toBeInTheDocument()
    expect(within(section).getByRole('link', { name: 'Nova transação' })).toHaveAttribute(
      'href',
      `/transactions/new?accountId=${NORTE}&returnTo=account`,
    )
    expect(screen.queryByText('Em breve')).not.toBeInTheDocument()
    expect(screen.getByText(/registrada como pendente/)).toBeInTheDocument()
    expect(await axeViolations(container)).toEqual([])
  })
})

describe('/accounts/new — saldo inicial', () => {
  async function createWithOpening(user: ReturnType<typeof userEvent.setup>, amount: string, negative = false) {
    await user.type(screen.getByLabelText('Nome da conta'), 'Conta Aurora')
    await user.type(screen.getByLabelText('Saldo de hoje'), amount)
    if (negative) await user.click(screen.getByRole('radio', { name: 'Negativo' }))
    await user.click(screen.getByRole('button', { name: 'Criar conta' }))
  }

  it('envia o saldo inicial (string decimal + direção) junto com a criação', async () => {
    const calls = stubBff({ 'POST /api/bff/accounts': () => json({ ...account(), openingBalance: 'RECORDED' }, 201) })
    const user = userEvent.setup()
    const { container } = render(<NewAccountView returnTo={null} />)
    expect(await axeViolations(container)).toEqual([])
    await createWithOpening(user, '1.500,00', true)
    await waitFor(() => expect(push).toHaveBeenCalledWith('/accounts'))
    expect(body(calls[0]!.init)).toEqual({
      name: 'Conta Aurora',
      type: 'CHECKING',
      institutionName: null,
      includedInTotal: true,
      openingBalance: { amount: '1500.00', direction: 'DECREASE' },
    })
  })

  it('sem saldo inicial: o corpo não leva openingBalance', async () => {
    const calls = stubBff({ 'POST /api/bff/accounts': () => json(account(), 201) })
    const user = userEvent.setup()
    render(<NewAccountView returnTo={null} />)
    await user.type(screen.getByLabelText('Nome da conta'), 'Conta Aurora')
    await user.click(screen.getByRole('button', { name: 'Criar conta' }))
    await waitFor(() => expect(push).toHaveBeenCalled())
    expect(body(calls[0]!.init)).not.toHaveProperty('openingBalance')
  })

  it('saldo inválido: erro no campo, sem criar a conta', async () => {
    const calls = stubBff({})
    const user = userEvent.setup()
    render(<NewAccountView returnTo={null} />)
    await createWithOpening(user, '12abc')
    await waitFor(() => expect(screen.getByLabelText('Saldo de hoje')).toHaveFocus())
    expect(screen.getByLabelText('Saldo de hoje')).toHaveAccessibleDescription(/Use só números/)
    expect(calls).toHaveLength(0)
  })

  it('ajuste falhou: conta criada, oferece tentar de novo (mesmo valor) ou seguir sem saldo', async () => {
    let retries = 0
    const calls = stubBff({
      'POST /api/bff/accounts/': () => (++retries === 1 ? json({ code: 'UNAVAILABLE' }, 502) : json({ openingBalance: 'RECORDED' })),
      'POST /api/bff/accounts': () => json({ ...account(), openingBalance: 'FAILED' }, 201),
    })
    const user = userEvent.setup()
    const { container } = render(<NewAccountView returnTo={null} />)
    await createWithOpening(user, '250')

    expect(await screen.findByRole('heading', { name: 'Conta criada, mas o saldo inicial não foi lançado' })).toBeInTheDocument()
    expect(push).not.toHaveBeenCalled()
    expect(await axeViolations(container)).toEqual([])

    await user.click(screen.getByRole('button', { name: 'Tentar lançar saldo inicial novamente' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível falar com o servidor')
    await user.click(screen.getByRole('button', { name: 'Tentar lançar saldo inicial novamente' }))
    await waitFor(() => expect(push).toHaveBeenCalledWith('/accounts'))

    const retriesCalls = calls.filter((c) => c.url === `/api/bff/accounts/${AURORA}/opening-balance`)
    expect(retriesCalls).toHaveLength(2)
    for (const call of retriesCalls) expect(body(call.init)).toEqual({ amount: '250.00', direction: 'INCREASE' })
  })

  it('saldo inicial já existia: avisa e só deixa continuar', async () => {
    stubBff({
      'POST /api/bff/accounts/': () => json({ code: 'OPENING_BALANCE_EXISTS' }, 409),
      'POST /api/bff/accounts': () => json({ ...account(), openingBalance: 'FAILED' }, 201),
    })
    const user = userEvent.setup()
    render(<NewAccountView returnTo={null} />)
    await createWithOpening(user, '250')
    await user.click(await screen.findByRole('button', { name: 'Tentar lançar saldo inicial novamente' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('já foi lançado')
    expect(screen.queryByRole('button', { name: 'Tentar lançar saldo inicial novamente' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Continuar' }))
    expect(push).toHaveBeenCalledWith('/accounts')
  })
})

describe('Mais (M-More)', () => {
  it('leva a Contas e Transações; itens futuros sinalizados', async () => {
    const { container } = render(<MoreView />)
    expect(screen.getByRole('link', { name: 'Transações' })).toHaveAttribute('href', '/transactions')
    expect(screen.getByRole('link', { name: 'Contas' })).toHaveAttribute('href', '/accounts')
    expect(screen.getByRole('link', { name: /Insights/ })).toHaveTextContent('Em breve')
    expect(await axeViolations(container)).toEqual([])
  })
})
