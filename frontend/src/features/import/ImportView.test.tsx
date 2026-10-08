import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { axeViolations } from '@/test/a11y'
import type { ImportBatch, ImportRecord } from './api'
import { ImportView } from './ImportView'

const replace = vi.fn()
let search = ''

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace }),
  usePathname: () => '/import',
  useSearchParams: () => new URLSearchParams(search),
}))

const AURORA = '01922f5e-0000-7000-8000-0000000000a1'
const NORTE = '01922f5e-0000-7000-8000-0000000000a2'
const IMPORT = '01922f5e-0000-7000-8000-0000000000b1'

const account = (id: string, name: string) => ({
  id,
  workspaceId: 'ws',
  name,
  type: 'CHECKING' as const,
  institutionName: name,
  currency: 'BRL',
  includedInTotal: true,
  status: 'ACTIVE' as const,
  createdAt: '2026-09-01T00:00:00Z',
  updatedAt: '2026-09-01T00:00:00Z',
})

const batch = (overrides: Partial<ImportBatch> = {}): ImportBatch => ({
  id: IMPORT,
  workspaceId: 'ws',
  accountId: AURORA,
  format: 'CSV',
  status: 'PREVIEW',
  fileName: 'extrato.csv',
  fileSize: 2048,
  lines: { total: 3, valid: 2, invalid: 1, duplicate: 0, imported: 0 },
  period: { from: '2026-09-01', to: '2026-09-03' },
  sameFileImportedBefore: false,
  failureReason: null,
  createdAt: '2026-09-27T12:00:00Z',
  previewExpiresAt: '2026-09-28T12:00:00Z',
  confirmedAt: null,
  completedAt: null,
  ...overrides,
})

const RECORDS: ImportRecord[] = [
  {
    lineNumber: 2,
    status: 'VALID',
    occurredOn: '2026-09-01',
    amount: { amount: '4500.00', currency: 'BRL' },
    direction: 'INFLOW',
    description: 'Salário',
    issue: null,
    transactionId: null,
  },
  {
    lineNumber: 3,
    status: 'VALID',
    occurredOn: '2026-09-02',
    amount: { amount: '86.40', currency: 'BRL' },
    direction: 'OUTFLOW',
    description: 'Bistrô Lume',
    issue: null,
    transactionId: null,
  },
  {
    lineNumber: 4,
    status: 'INVALID',
    occurredOn: null,
    amount: null,
    direction: null,
    description: null,
    issue: { field: 'amount', code: 'INVALID_AMOUNT' },
    transactionId: null,
  },
]

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

type Route = (init: RequestInit | undefined, url: string) => Response | Promise<Response>

/** fetch falso roteado por método + prefixo de URL; registra cada chamada. */
function stubBff(routes: Record<string, Route>) {
  const calls: { method: string; url: string; init?: RequestInit }[] = []
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    calls.push({ method, url, init })
    const match = Object.keys(routes)
      .sort((a, b) => b.length - a.length)
      .find((key) => {
        const [m, prefix] = key.split(' ')
        return m === method && url.startsWith(prefix!)
      })
    return match ? routes[match]!(init, url) : json({ code: 'UNAVAILABLE' }, 502)
  })
  vi.stubGlobal('fetch', fetchMock)
  return calls
}

const csv = (name = 'extrato.csv') => new File(['data;descricao;valor\n01/09/2026;X;-1,00\n'], name, { type: 'text/csv' })

beforeEach(() => {
  search = ''
  replace.mockReset()
})
afterEach(() => vi.unstubAllGlobals())

describe('ImportView — envio', () => {
  it('escolhe conta e arquivo, envia ao BFF e abre o preview pela URL', async () => {
    const calls = stubBff({
      'GET /api/bff/accounts': () => json({ items: [account(AURORA, 'Banco Aurora'), account(NORTE, 'Banco Norte')] }),
      'POST /api/bff/imports': () => json(batch(), 201),
    })
    const user = userEvent.setup()
    render(<ImportView />)

    const submit = await screen.findByRole('button', { name: 'Continuar' })
    expect(submit).toBeDisabled()

    await user.click(screen.getByRole('radio', { name: /Banco Norte/ }))
    await user.upload(screen.getByLabelText('Selecionar arquivo'), csv())
    expect(screen.getByText('extrato.csv')).toBeInTheDocument()
    await user.click(submit)

    await waitFor(() => expect(replace).toHaveBeenCalledWith(`/import?id=${IMPORT}`, { scroll: true }))
    const upload = calls.find((c) => c.method === 'POST')!
    const form = upload.init!.body as FormData
    expect(form.get('accountId')).toBe(NORTE)
    expect((form.get('file') as File).name).toBe('extrato.csv')
    expect((upload.init!.headers as Record<string, string>)['Idempotency-Key']).toMatch(/^web:[0-9a-f-]{36}$/)
  })

  it('com uma única conta, ela já vem escolhida; ?accountId= também pré-seleciona', async () => {
    stubBff({ 'GET /api/bff/accounts': () => json({ items: [account(AURORA, 'Banco Aurora')] }) })
    render(<ImportView />)
    expect(await screen.findByRole('radio', { name: /Banco Aurora/ })).toBeChecked()
  })

  it('arquivo de outro tipo é recusado no navegador, sem chamar o BFF', async () => {
    const calls = stubBff({ 'GET /api/bff/accounts': () => json({ items: [account(AURORA, 'Banco Aurora')] }) })
    const user = userEvent.setup({ applyAccept: false })
    render(<ImportView />)

    await user.upload(await screen.findByLabelText('Selecionar arquivo'), new File(['x'], 'planilha.xlsx'))

    expect(screen.getByRole('alert')).toHaveTextContent('Use um arquivo .ofx ou .csv')
    expect(screen.getByRole('button', { name: 'Continuar' })).toBeDisabled()
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(0)
  })

  it('arquivo recusado pelo servidor mostra o motivo traduzido', async () => {
    stubBff({
      'GET /api/bff/accounts': () => json({ items: [account(AURORA, 'Banco Aurora')] }),
      'POST /api/bff/imports': () => json({ code: 'FILE_REJECTED', reason: 'MISSING_COLUMN' }, 422),
    })
    const user = userEvent.setup()
    render(<ImportView />)

    await user.upload(await screen.findByLabelText('Selecionar arquivo'), csv())
    await user.click(screen.getByRole('button', { name: 'Continuar' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Faltam colunas obrigatórias')
    expect(replace).not.toHaveBeenCalled()
  })

  it('reenviar depois de falha de rede usa a mesma Idempotency-Key', async () => {
    let attempt = 0
    const calls = stubBff({
      'GET /api/bff/accounts': () => json({ items: [account(AURORA, 'Banco Aurora')] }),
      'POST /api/bff/imports': () => (++attempt === 1 ? json({ code: 'UNAVAILABLE' }, 502) : json(batch(), 201)),
    })
    const user = userEvent.setup()
    render(<ImportView />)

    await user.upload(await screen.findByLabelText('Selecionar arquivo'), csv())
    await user.click(screen.getByRole('button', { name: 'Continuar' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível falar com o servidor')
    await user.click(screen.getByRole('button', { name: 'Continuar' }))

    await waitFor(() => expect(replace).toHaveBeenCalled())
    const keys = calls
      .filter((c) => c.method === 'POST')
      .map((c) => (c.init!.headers as Record<string, string>)['Idempotency-Key'])
    expect(keys).toHaveLength(2)
    expect(keys[0]).toBe(keys[1])
  })

  it('sem contas: orienta a criar uma antes', async () => {
    stubBff({ 'GET /api/bff/accounts': () => json({ items: [] }) })
    render(<ImportView />)
    expect(await screen.findByRole('heading', { name: 'Adicione uma conta antes de importar' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Adicionar conta' })).toHaveAttribute('href', '/accounts/new')
  })

  it('formulário sem violações de acessibilidade', async () => {
    stubBff({ 'GET /api/bff/accounts': () => json({ items: [account(AURORA, 'Banco Aurora'), account(NORTE, 'Banco Norte')] }) })
    const { container } = render(<ImportView />)
    await screen.findByRole('radio', { name: /Banco Norte/ })
    expect(await axeViolations(container)).toEqual([])
  })
})

describe('ImportView — preview, confirmação e resultado', () => {
  beforeEach(() => {
    search = `id=${IMPORT}`
  })

  it('mostra contagens e linhas (com motivo das recusadas), confirma e acompanha até concluir', async () => {
    let polls = 0
    const calls = stubBff({
      [`GET /api/bff/imports/${IMPORT}/records`]: () => json({ items: RECORDS, page: 0, pageSize: 50, totalItems: 3 }),
      [`GET /api/bff/imports/${IMPORT}`]: () =>
        json(
          polls++ === 0
            ? batch()
            : batch({ status: 'COMPLETED', lines: { total: 3, valid: 2, invalid: 1, duplicate: 0, imported: 2 } }),
        ),
      [`POST /api/bff/imports/${IMPORT}/confirm`]: () => json(batch({ status: 'CONFIRMED' }), 202),
    })
    const user = userEvent.setup()
    const { container } = render(<ImportView />)

    const counts = await screen.findByRole('region', { name: 'extrato.csv' })
    expect(within(counts).getByText('Novas para importar').nextSibling).toHaveTextContent('2')
    expect(within(counts).getByText('Com problema').nextSibling).toHaveTextContent('1')
    expect(await screen.findByText('Valor inválido')).toBeInTheDocument()
    expect(screen.getByText('Bistrô Lume')).toBeInTheDocument()
    expect(screen.getByText('Linha 4')).toBeInTheDocument()
    expect(await axeViolations(container)).toEqual([])

    await user.click(screen.getByRole('button', { name: 'Importar 2 movimentações' }))

    expect(await screen.findByRole('heading', { name: 'Importando 2 movimentações…' })).toBeInTheDocument()
    expect(
      await screen.findByRole('heading', { name: '2 movimentações importadas' }, { timeout: 4000 }),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ver meu panorama' })).toHaveAttribute('href', '/home')
    expect(calls.filter((c) => c.method === 'POST')).toHaveLength(1)
  })

  it('sem linhas novas: confirmação desabilitada e aviso de arquivo já importado', async () => {
    stubBff({
      [`GET /api/bff/imports/${IMPORT}/records`]: () => json({ items: [], page: 0, pageSize: 50, totalItems: 0 }),
      [`GET /api/bff/imports/${IMPORT}`]: () =>
        json(batch({ sameFileImportedBefore: true, lines: { total: 2, valid: 0, invalid: 0, duplicate: 2, imported: 0 } })),
    })
    render(<ImportView />)

    expect(await screen.findByRole('button', { name: 'Nada para importar' })).toBeDisabled()
    expect(screen.getByText(/Este arquivo já foi importado nesta conta/)).toBeInTheDocument()
  })

  it('descartar mostra o estado cancelado', async () => {
    stubBff({
      [`GET /api/bff/imports/${IMPORT}/records`]: () => json({ items: RECORDS, page: 0, pageSize: 50, totalItems: 3 }),
      [`GET /api/bff/imports/${IMPORT}`]: () => json(batch()),
      [`POST /api/bff/imports/${IMPORT}/cancel`]: () => json(batch({ status: 'CANCELLED' })),
    })
    const user = userEvent.setup()
    render(<ImportView />)

    await user.click(await screen.findByRole('button', { name: 'Descartar' }))
    expect(await screen.findByRole('heading', { name: 'Importação descartada' })).toBeInTheDocument()
  })

  it('falha no processamento explica o motivo sem jargão', async () => {
    stubBff({
      [`GET /api/bff/imports/${IMPORT}`]: () => json(batch({ status: 'FAILED', failureReason: 'ACCOUNT_ARCHIVED' })),
    })
    render(<ImportView />)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('A importação não foi concluída')
    expect(alert).toHaveTextContent('A conta foi arquivada')
    expect(alert).not.toHaveTextContent('ACCOUNT_ARCHIVED')
  })

  it('importação inexistente ou de outro Workspace', async () => {
    stubBff({ [`GET /api/bff/imports/${IMPORT}`]: () => json({ code: 'IMPORT_NOT_FOUND' }, 404) })
    render(<ImportView />)
    expect(await screen.findByRole('heading', { name: 'Importação não encontrada' })).toBeInTheDocument()
  })

  it('id inválido na URL volta para o envio', async () => {
    search = 'id=nao-e-uuid'
    stubBff({ 'GET /api/bff/accounts': () => json({ items: [] }) })
    render(<ImportView />)
    expect(await screen.findByRole('heading', { name: 'Adicione uma conta antes de importar' })).toBeInTheDocument()
  })
})
