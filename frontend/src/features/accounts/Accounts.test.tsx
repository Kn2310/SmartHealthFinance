import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { brl, readyOverview } from '@/features/home/fixtures'
import { axeViolations } from '@/test/a11y'
import { json, stubBff } from '@/test/bff-stub'
import type { Account } from './api'
import { AccountDetailView } from './AccountDetailView'
import { AccountsView } from './AccountsView'
import { NewAccountView } from './NewAccountView'

const push = vi.fn()

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push, replace: vi.fn() }),
  usePathname: () => '/accounts',
  useSearchParams: () => new URLSearchParams(),
}))

const AURORA = '01922f5e-0000-7000-8000-0000000000a1'
const NORTE = '01922f5e-0000-7000-8000-0000000000a2'
const ANTIGA = '01922f5e-0000-7000-8000-0000000000a3'

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

const overview = readyOverview({
  summary: { totalBalance: brl('6240.35'), balanceAsOf: '2026-10-08', accountCount: 2, pendingTransactions: 0 },
  accounts: [
    { id: AURORA, name: 'Conta Aurora', type: 'CHECKING', institutionName: 'Banco Aurora', includedInTotal: true, balance: brl('6240.35'), movementCount: 3 },
    { id: NORTE, name: 'Reserva', type: 'SAVINGS', institutionName: null, includedInTotal: false, balance: brl('1000.00'), movementCount: 1 },
  ],
})

/** Seção Movimentações do detalhe: lista vazia da conta e nomes das contas. */
const MOVEMENTS = {
  'GET /api/bff/transactions': () => json({ items: [], page: 0, pageSize: 50, totalItems: 0 }),
  'GET /api/bff/accounts?': () => json({ items: [account()] }),
}

const body = (init: RequestInit | undefined) => JSON.parse(String(init?.body)) as unknown

beforeEach(() => push.mockReset())
afterEach(() => vi.unstubAllGlobals())

describe('/accounts/new — criar conta manual', () => {
  it('cria a conta no BFF e volta para a lista', async () => {
    const calls = stubBff({ 'POST /api/bff/accounts': () => json(account({ id: NORTE }), 201) })
    const user = userEvent.setup()
    const { container } = render(<NewAccountView returnTo={null} />)

    expect(screen.getByRole('heading', { level: 1, name: 'Adicionar conta' })).toBeInTheDocument()
    expect(screen.getByText(/Open Finance/)).toBeInTheDocument()
    expect(await axeViolations(container)).toEqual([])

    await user.type(screen.getByLabelText('Nome da conta'), '  Reserva  ')
    await user.type(screen.getByLabelText(/Instituição/), 'Banco Norte')
    await user.selectOptions(screen.getByLabelText('Tipo'), 'SAVINGS')
    await user.click(screen.getByRole('switch', { name: 'Incluir no saldo total' }))
    await user.click(screen.getByRole('button', { name: 'Criar conta' }))

    await waitFor(() => expect(push).toHaveBeenCalledWith('/accounts'))
    expect(calls).toHaveLength(1)
    expect(body(calls[0]!.init)).toEqual({ name: 'Reserva', type: 'SAVINGS', institutionName: 'Banco Norte', includedInTotal: false })
    expect(screen.getByRole('button', { name: 'Criando conta…' })).toBeDisabled()
  })

  it('vindo da importação: volta para /import com a nova conta selecionada', async () => {
    stubBff({ 'POST /api/bff/accounts': () => json(account({ id: NORTE }), 201) })
    const user = userEvent.setup()
    render(<NewAccountView returnTo="import" />)

    expect(screen.getByRole('link', { name: 'Cancelar' })).toHaveAttribute('href', '/import')
    await user.type(screen.getByLabelText('Nome da conta'), 'Conta Norte')
    await user.click(screen.getByRole('button', { name: 'Criar e continuar' }))

    await waitFor(() => expect(push).toHaveBeenCalledWith(`/import?accountId=${NORTE}`))
  })

  it('nome vazio: erro no campo, associado a ele, com foco — sem chamar o BFF', async () => {
    const calls = stubBff({})
    const user = userEvent.setup()
    const { container } = render(<NewAccountView returnTo={null} />)

    await user.type(screen.getByLabelText('Nome da conta'), '   ')
    await user.click(screen.getByRole('button', { name: 'Criar conta' }))

    const name = screen.getByLabelText('Nome da conta')
    await waitFor(() => expect(name).toHaveFocus())
    expect(name).toHaveAttribute('aria-invalid', 'true')
    expect(name).toHaveAccessibleDescription(/Informe o nome da conta\./)
    expect(calls).toHaveLength(0)
    expect(await axeViolations(container)).toEqual([])
  })

  it('validação do servidor: mensagem no campo recusado e foco nele', async () => {
    stubBff({
      'POST /api/bff/accounts': () =>
        json({ code: 'VALIDATION_FAILED', details: [{ field: 'institutionName', code: 'INVALID_CHARACTERS' }] }, 400),
    })
    const user = userEvent.setup()
    render(<NewAccountView returnTo={null} />)

    await user.type(screen.getByLabelText('Nome da conta'), 'Conta')
    await user.type(screen.getByLabelText(/Instituição/), 'Banco')
    await user.click(screen.getByRole('button', { name: 'Criar conta' }))

    const institution = screen.getByLabelText(/Instituição/)
    await waitFor(() => expect(institution).toHaveFocus())
    expect(institution).toHaveAccessibleDescription(/caracteres que não podem ser usados/)
    expect(screen.getByLabelText('Nome da conta')).not.toHaveAttribute('aria-invalid')
    expect(screen.getByRole('button', { name: 'Criar conta' })).toBeEnabled()
    expect(push).not.toHaveBeenCalled()
  })

  it('falha do servidor: mensagem neutra, sem código cru', async () => {
    stubBff({ 'POST /api/bff/accounts': () => json({ code: 'UNAVAILABLE' }, 502) })
    const user = userEvent.setup()
    render(<NewAccountView returnTo={null} />)

    await user.type(screen.getByLabelText('Nome da conta'), 'Conta')
    await user.click(screen.getByRole('button', { name: 'Criar conta' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Seus dados continuam seguros')
    expect(screen.queryByText(/UNAVAILABLE/)).not.toBeInTheDocument()
  })
})

describe('/accounts — lista', () => {
  it('sem nenhuma conta: estado vazio com criar manual e importar (que começa criando a conta)', async () => {
    stubBff({ 'GET /api/bff/accounts': () => json({ items: [] }), 'GET /api/bff/overview': () => json(overview) })
    const { container } = render(<AccountsView />)

    expect(await screen.findByRole('heading', { name: 'Nenhuma conta adicionada ainda' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Criar conta manual' })).toHaveAttribute('href', '/accounts/new')
    expect(screen.getByRole('link', { name: 'Importar extrato' })).toHaveAttribute('href', '/accounts/new?returnTo=import')
    expect(await axeViolations(container)).toEqual([])
  })

  it('mostra saldos do Overview, status e "Importar extrato" por conta; arquivadas só no filtro', async () => {
    const calls = stubBff({
      'GET /api/bff/accounts': () =>
        json({
          items: [
            account(),
            account({ id: NORTE, name: 'Reserva', type: 'SAVINGS', institutionName: null, includedInTotal: false }),
            account({ id: ANTIGA, name: 'Conta Antiga', status: 'ARCHIVED' }),
          ],
        }),
      'GET /api/bff/overview': () => json(overview),
    })
    const user = userEvent.setup()
    const { container } = render(<AccountsView />)

    const list = await screen.findByRole('list')
    expect(calls.find((c) => c.url.startsWith('/api/bff/accounts'))?.url).toBe('/api/bff/accounts?includeArchived=true')
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Saldo total em 8 out.' })).toBeInTheDocument())

    const aurora = within(list).getByRole('article', { name: 'Conta Aurora' })
    expect(within(aurora).getByText('R$ 6.240,35')).toBeInTheDocument()
    expect(within(aurora).getByText('Ativa')).toBeInTheDocument()
    expect(within(aurora).getByRole('link', { name: 'Conta Aurora' })).toHaveAttribute('href', `/accounts/${AURORA}`)
    expect(within(aurora).getByRole('link', { name: 'Importar extrato para Conta Aurora' })).toHaveAttribute(
      'href',
      `/import?accountId=${AURORA}`,
    )
    expect(within(list).getByRole('article', { name: 'Reserva' })).toHaveTextContent('Fora do saldo total')
    expect(screen.queryByRole('article', { name: 'Conta Antiga' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('checkbox', { name: 'Mostrar arquivadas (1)' }))
    const archived = screen.getByRole('article', { name: 'Conta Antiga' })
    expect(within(archived).getByText('Arquivada')).toBeInTheDocument()
    expect(within(archived).getByText('Não entra no saldo')).toBeInTheDocument()
    expect(within(archived).queryByRole('link', { name: /Importar extrato/ })).not.toBeInTheDocument()
    expect(await axeViolations(container)).toEqual([])
  })

  it('só arquivadas: diferencia "nenhuma ativa" de primeira vez', async () => {
    stubBff({
      'GET /api/bff/accounts': () => json({ items: [account({ status: 'ARCHIVED' })] }),
      'GET /api/bff/overview': () => json(overview),
    })
    render(<AccountsView />)
    expect(await screen.findByText('Nenhuma conta ativa')).toBeInTheDocument()
    expect(screen.queryByText('Nenhuma conta adicionada ainda')).not.toBeInTheDocument()
  })

  it('saldos indisponíveis: contas aparecem sem saldo e com aviso (parcial)', async () => {
    stubBff({ 'GET /api/bff/accounts': () => json({ items: [account()] }), 'GET /api/bff/overview': () => json({ code: 'UNAVAILABLE' }, 502) })
    render(<AccountsView />)
    expect(await screen.findByText(/Não conseguimos carregar os saldos agora/)).toBeInTheDocument()
    expect(screen.getByRole('article', { name: 'Conta Aurora' })).toHaveTextContent('sem dados')
  })

  it('erro ao carregar as contas: mensagem sem jargão e "Tentar novamente"', async () => {
    let fail = true
    stubBff({
      'GET /api/bff/accounts': () => (fail ? json({ code: 'UNAVAILABLE' }, 502) : json({ items: [account()] })),
      'GET /api/bff/overview': () => json(overview),
    })
    const user = userEvent.setup()
    render(<AccountsView />)

    expect(await screen.findByRole('heading', { name: 'Não foi possível carregar suas contas' })).toBeInTheDocument()
    fail = false
    await user.click(screen.getByRole('button', { name: 'Tentar novamente' }))
    expect(await screen.findByRole('article', { name: 'Conta Aurora' })).toBeInTheDocument()
  })
})

describe('/accounts/{id} — detalhe e edição', () => {
  it('edita e salva com substituição completa', async () => {
    const calls = stubBff({
      'GET /api/bff/accounts/': () => json(account()),
      'GET /api/bff/overview': () => json(overview),
      ...MOVEMENTS,
      'PUT /api/bff/accounts/': (init) => json(account({ ...(body(init) as object), updatedAt: '2026-10-08T00:00:00Z' })),
    })
    const user = userEvent.setup()
    const { container } = render(<AccountDetailView id={AURORA} />)

    expect(await screen.findByRole('heading', { level: 1, name: 'Conta Aurora' })).toBeInTheDocument()
    expect(await screen.findByText('R$ 6.240,35')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Importar extrato' })).toHaveAttribute('href', `/import?accountId=${AURORA}`)
    expect(await axeViolations(container)).toEqual([])

    const name = screen.getByLabelText('Nome da conta')
    await user.clear(name)
    await user.type(name, 'Aurora Principal')
    await user.click(screen.getByRole('switch', { name: 'Incluir no saldo total' }))
    await user.click(screen.getByRole('button', { name: 'Salvar alterações' }))

    expect(await screen.findByText('Alterações salvas.')).toBeInTheDocument()
    const put = calls.find((c) => c.method === 'PUT')!
    expect(put.url).toBe(`/api/bff/accounts/${AURORA}`)
    expect(body(put.init)).toEqual({ name: 'Aurora Principal', type: 'CHECKING', institutionName: 'Banco Aurora', includedInTotal: false })
    expect(screen.getByRole('heading', { level: 1, name: 'Aurora Principal' })).toBeInTheDocument()
  })

  it('conta arquivada: só leitura, explica e reativa', async () => {
    const calls = stubBff({
      'GET /api/bff/accounts/': () => json(account({ status: 'ARCHIVED' })),
      'GET /api/bff/overview': () => json(overview),
      ...MOVEMENTS,
      'POST /api/bff/accounts/': () => json(account()),
    })
    const user = userEvent.setup()
    const { container } = render(<AccountDetailView id={AURORA} />)

    expect(await screen.findByRole('heading', { name: 'Conta arquivada' })).toBeInTheDocument()
    expect(screen.getByLabelText('Nome da conta')).toBeDisabled()
    expect(screen.queryByRole('button', { name: 'Salvar alterações' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Importar extrato' })).not.toBeInTheDocument()
    expect(await axeViolations(container)).toEqual([])

    await user.click(screen.getByRole('button', { name: 'Reativar conta' }))
    expect(await screen.findByText(/Conta reativada/)).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'POST')?.url).toBe(`/api/bff/accounts/${AURORA}/reactivate`)
    expect(screen.getByLabelText('Nome da conta')).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Arquivar conta' })).toBeInTheDocument()
  })

  it('arquiva a conta (reversível, histórico mantido)', async () => {
    const calls = stubBff({
      'GET /api/bff/accounts/': () => json(account()),
      'GET /api/bff/overview': () => json(overview),
      ...MOVEMENTS,
      'POST /api/bff/accounts/': () => json(account({ status: 'ARCHIVED' })),
    })
    const user = userEvent.setup()
    render(<AccountDetailView id={AURORA} />)

    await user.click(await screen.findByRole('button', { name: 'Arquivar conta' }))
    expect(await screen.findByText(/Conta arquivada\. O histórico foi mantido/)).toBeInTheDocument()
    expect(calls.find((c) => c.method === 'POST')?.url).toBe(`/api/bff/accounts/${AURORA}/archive`)
    expect(screen.getByRole('button', { name: 'Reativar conta' })).toBeInTheDocument()
  })

  it('edição concorrente (CONFLICT): explica e oferece recarregar', async () => {
    stubBff({
      'GET /api/bff/accounts/': () => json(account()),
      'GET /api/bff/overview': () => json(overview),
      ...MOVEMENTS,
      'PUT /api/bff/accounts/': () => json({ code: 'CONFLICT' }, 409),
    })
    const user = userEvent.setup()
    render(<AccountDetailView id={AURORA} />)

    await user.click(await screen.findByRole('button', { name: 'Salvar alterações' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('alterada em outra janela')
    expect(screen.getByRole('button', { name: 'Recarregar dados' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Salvar alterações' })).toBeEnabled()
  })

  it('conta de outro Workspace ou inexistente: "não encontrada", sem detalhes', async () => {
    stubBff({ 'GET /api/bff/accounts/': () => json({ code: 'ACCOUNT_NOT_FOUND' }, 404), 'GET /api/bff/overview': () => json(overview), ...MOVEMENTS })
    const { container } = render(<AccountDetailView id={AURORA} />)
    expect(await screen.findByRole('heading', { name: 'Conta não encontrada' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ver minhas contas' })).toHaveAttribute('href', '/accounts')
    expect(await axeViolations(container)).toEqual([])
  })

  it('id inválido na URL nem chega ao BFF', () => {
    const calls = stubBff({})
    render(<AccountDetailView id={null} />)
    expect(screen.getByRole('heading', { name: 'Conta não encontrada' })).toBeInTheDocument()
    expect(calls).toHaveLength(0)
  })
})
