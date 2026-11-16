// Testes do seed sem rede externa: funções puras + o fluxo completo contra uma API simulada em localhost.
// Rodar: node --test infrastructure/staging/seed/
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { test } from 'node:test'
import {
  addMonths,
  apiAmount,
  businessToday,
  cardPeriodStart,
  cardStatementCsv,
  cardStatementLines,
  csvAmount,
  historyStart,
  readEnv,
  run,
  splitCents,
  statementCsv,
  statementLines,
  sumCents,
} from './seed.mjs'

test('datas relativas: meses com fim de mês curto e histórico de 6 meses', () => {
  assert.equal(addMonths('2026-03-31', -1), '2026-02-28')
  assert.equal(addMonths('2026-11-16', 10), '2027-09-16')
  assert.equal(addMonths('2026-01-15', -2), '2025-11-15')
  assert.equal(historyStart('2026-11-16'), '2026-06-01')
  assert.equal(historyStart('2027-02-03'), '2026-09-01')
  assert.match(businessToday(), /^\d{4}-\d{2}-\d{2}$/)
})

test('início da fatura atual do cartão que fecha no dia 28', () => {
  assert.equal(cardPeriodStart('2026-11-16'), '2026-10-28')
  assert.equal(cardPeriodStart('2026-11-28'), '2026-11-28')
  assert.equal(cardPeriodStart('2027-01-10'), '2026-12-28')
})

test('dinheiro em centavos BigInt: formatos e divisão sem perder centavo', () => {
  assert.equal(csvAmount(-180000n), '-1800,00')
  assert.equal(csvAmount(5n), '0,05')
  assert.equal(apiAmount(624035n), '6240.35')
  const parts = splitCents(29608n, 3)
  assert.equal(sumCents(parts), 29608n)
  assert.deepEqual(parts, [9870n, 9869n, 9869n])
})

for (const today of ['2026-11-16', '2026-11-05', '2027-03-31', '2026-01-02']) {
  test(`extrato de ${today}: nada no futuro, ids únicos, histórico de 6 meses`, () => {
    const lines = statementLines(today)
    assert.ok(lines.length > 0)
    assert.ok(lines.every((l) => l.date <= today))
    assert.equal(new Set(lines.map((l) => l.id)).size, lines.length)
    assert.ok(lines[0].date >= historyStart(today))
    assert.ok(lines.every((l, i) => i === 0 || lines[i - 1].date <= l.date))
    assert.ok(lines.every((l) => typeof l.cents === 'bigint' && l.cents !== 0n))
    const months = new Set(lines.map((l) => l.date.slice(0, 7)))
    assert.ok(months.size >= 6, 'cobre 6 meses')
  })
}

test('mês cheio reproduz o exemplo: Moradia 1.986,32; alimentação fora 1.284,00 em 25 compras; despesas 3.846,20', () => {
  const lines = statementLines('2026-11-30')
  const month = lines.filter((l) => l.date.startsWith('2026-11'))
  const expenses = month.filter((l) => l.cents < 0n)
  const total = (pattern) => -sumCents(expenses.filter((l) => pattern.test(l.description)).map((l) => l.cents))
  assert.equal(total(/Aluguel|Energia/), 198632n)
  const dining = expenses.filter((l) => /Entrega|Bistrô|Pizzaria|Café/.test(l.description))
  assert.equal(dining.length, 25)
  assert.equal(-sumCents(dining.map((l) => l.cents)), 128400n)
  assert.equal(-sumCents(expenses.map((l) => l.cents)), 384620n)
  assert.equal(sumCents(month.filter((l) => l.cents > 0n).map((l) => l.cents)), 680000n)
})

test('CSV do extrato segue o modelo canônico (cabeçalho, dd/MM/yyyy, vírgula decimal, sem milhar)', () => {
  const csv = statementCsv(statementLines('2026-11-16'))
  const [header, first, ...rest] = csv.trim().split('\r\n')
  assert.equal(header, 'data;descricao;valor;id')
  assert.match(first, /^\d{2}\/\d{2}\/\d{4};[^;]+;-?\d+,\d{2};SEED-\d{6}-\d{3}$/)
  assert.ok(rest.every((row) => row.split(';').length === 4))
  assert.ok(!csv.includes('NaN') && !/\d\.\d{3},/.test(csv))
})

test('fatura do Aurora: notebook 3/10 de R$ 480,00 dois ciclos antes e compras do ciclo até hoje', () => {
  for (const today of ['2026-11-16', '2026-11-28', '2026-11-29', '2026-12-02']) {
    const lines = cardStatementLines(today)
    const start = cardPeriodStart(today)
    const [notebook, ...purchases] = lines
    assert.equal(notebook.cents, 48000n)
    assert.equal(notebook.installment, '3/10')
    assert.equal(notebook.date, addMonths(start, -2))
    assert.ok(purchases.every((l) => l.date >= start && l.date <= today && l.installment === ''))
  }
  const csv = cardStatementCsv(cardStatementLines('2026-11-16'))
  assert.equal(csv.split('\r\n')[0], 'data;descricao;valor;parcela;id')
  assert.match(csv, /;480,00;3\/10;SEED-CARD-001/)
})

test('readEnv lista as variáveis ausentes sem imprimir valores', () => {
  assert.throws(() => readEnv({ SEED_API_BASE_URL: 'http://x' }), (e) => {
    assert.match(e.message, /SEED_TOKEN_URL/)
    assert.match(e.message, /SEED_MARINA_PASSWORD/)
    assert.ok(!e.message.includes('http://x'))
    return true
  })
})

// ------------------------------------------------------------------------------------------------ API simulada

const SECRETS = { client: 'segredo-do-client', marina: 'senha-marina', bruno: 'senha-bruno' }

function mockApi() {
  const users = new Map() // token → { ws, accounts[] }
  const state = { transactions: [], imports: new Map(), cards: [], goals: [], accountsCreated: 0, readinessFailures: 1 }
  let seq = 0
  const id = (prefix) => `${prefix}-${++seq}`

  const server = createServer(async (req, res) => {
    const chunks = []
    for await (const chunk of req) chunks.push(chunk)
    const raw = Buffer.concat(chunks)
    const url = new URL(req.url, 'http://localhost')
    const send = (status, body) => {
      res.writeHead(status, { 'content-type': 'application/json' })
      res.end(body === undefined ? '' : JSON.stringify(body))
    }

    if (url.pathname === '/token') {
      const form = new URLSearchParams(raw.toString())
      const ok =
        form.get('grant_type') === 'password' &&
        form.get('scope') === 'openid' &&
        form.get('client_secret') === SECRETS.client &&
        form.get('password') === SECRETS[form.get('username')]
      return ok ? send(200, { access_token: `tok-${form.get('username')}` }) : send(401, { error: 'invalid' })
    }

    const token = (req.headers.authorization ?? '').replace('Bearer ', '')
    const name = token.replace('tok-', '')
    if (!SECRETS[name] || name === 'client') return send(401, {})
    if (!users.has(token)) users.set(token, { ws: `ws-${name}`, accounts: [] })
    const user = users.get(token)

    if (req.method === 'POST' && url.pathname === '/api/v1/users/me') {
      if (state.readinessFailures-- > 0) return send(503, {})
      return send(201, { workspaceId: user.ws })
    }
    const m = /^\/api\/v1\/workspaces\/([^/]+)(\/.*)$/.exec(url.pathname)
    if (!m || m[1] !== user.ws) return send(404, { code: 'WORKSPACE_NOT_FOUND' })
    const [, , rest] = m
    const json = () => JSON.parse(raw.toString())

    if (rest === '/accounts' && req.method === 'GET') return send(200, { items: user.accounts })
    if (rest === '/accounts' && req.method === 'POST') {
      const account = { id: id('acc'), ...json() }
      user.accounts.push(account)
      state.accountsCreated++
      return send(201, account)
    }
    if (rest === '/transactions' && req.method === 'POST') {
      assert.ok(req.headers['idempotency-key'])
      const body = json()
      assert.match(body.amount.amount, /^\d+\.\d{2}$/)
      assert.equal(body.type, 'ADJUSTMENT')
      state.transactions.push(body)
      return send(201, { id: id('tx') })
    }
    if (rest === '/imports' && req.method === 'POST') {
      assert.ok(req.headers['idempotency-key'])
      const csv = new TextDecoder().decode(raw)
      const rows = (csv.match(/\r\n[0-9]{2}\/[0-9]{2}\/[0-9]{4};/g) ?? []).length
      const batch = { id: id('imp'), status: 'PREVIEW', polls: 0, lines: { valid: rows, invalid: 0, duplicate: 0 } }
      state.imports.set(batch.id, { ...batch, target: url.search })
      return send(201, batch)
    }
    const imp = /^\/imports\/([^/]+)(\/confirm)?$/.exec(rest)
    if (imp) {
      const batch = state.imports.get(imp[1])
      if (imp[2]) {
        batch.status = 'CONFIRMED'
        return send(202, batch)
      }
      if (++batch.polls >= 2) {
        batch.status = 'COMPLETED'
        batch.lines.imported = batch.lines.valid
      }
      return send(200, batch)
    }
    if (rest === '/cards' && req.method === 'POST') {
      const card = { id: id('card'), ...json() }
      state.cards.push(card)
      return send(201, card)
    }
    if (rest === '/goals' && req.method === 'POST') {
      state.goals.push(json())
      return send(201, { id: id('goal') })
    }
    return send(404, {})
  })
  return { server, state }
}

test('fluxo completo contra a API simulada: carrega, é idempotente e não vaza segredo no log', async () => {
  const { server, state } = mockApi()
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  const base = `http://127.0.0.1:${server.address().port}`
  const env = {
    SEED_API_BASE_URL: `${base}/`,
    SEED_TOKEN_URL: `${base}/token`,
    SEED_CLIENT_ID: 'shf-staging-seed',
    SEED_CLIENT_SECRET: SECRETS.client,
    SEED_MARINA_USERNAME: 'marina',
    SEED_MARINA_PASSWORD: SECRETS.marina,
    SEED_BRUNO_USERNAME: 'bruno',
    SEED_BRUNO_PASSWORD: SECRETS.bruno,
  }
  const logs = []
  const deps = { log: (l) => logs.push(l), sleep: async () => {}, today: '2026-11-16' }

  try {
    await run(env, deps)
    assert.equal(state.accountsCreated, 4)
    assert.equal(state.transactions.length, 4)
    assert.equal(state.cards.length, 2)
    assert.equal(state.goals.length, 2)
    assert.equal(state.imports.size, 2)
    assert.ok([...state.imports.values()].every((i) => i.status === 'COMPLETED'))
    assert.deepEqual(state.goals.map((g) => g.type), ['EMERGENCY_RESERVE', 'PURCHASE'])
    assert.equal(state.goals[1].deadline, '2027-09')

    // Saldos finais do exemplo: ajuste do Aurora + soma do extrato = R$ 6.240,35.
    const aurora = state.transactions[0]
    const signed = (aurora.adjustmentDirection === 'INCREASE' ? 1n : -1n) * BigInt(aurora.amount.amount.replace('.', ''))
    assert.equal(signed + sumCents(statementLines('2026-11-16').map((l) => l.cents)), 624035n)

    await run(env, deps)
    assert.equal(state.accountsCreated, 4, 'segunda execução não duplica')
    assert.equal(state.imports.size, 2)
    assert.ok(logs.some((l) => l.includes('seed pulado')))

    const output = logs.join('\n')
    for (const secret of [...Object.values(SECRETS), 'tok-', 'Bearer', '@example.com']) {
      assert.ok(!output.includes(secret), `log não pode conter ${secret}`)
    }
    assert.ok(!/\d+[.,]\d{2}\b/.test(output.replace(/HTTP \d+/g, '')), 'log sem valores monetários')
  } finally {
    server.close()
  }
})

test('API que nunca fica pronta: erro claro depois das tentativas', async () => {
  const { server, state } = mockApi()
  state.readinessFailures = 1000
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  const base = `http://127.0.0.1:${server.address().port}`
  const env = {
    SEED_API_BASE_URL: base,
    SEED_TOKEN_URL: `${base}/token`,
    SEED_CLIENT_ID: 'c',
    SEED_CLIENT_SECRET: SECRETS.client,
    SEED_MARINA_USERNAME: 'marina',
    SEED_MARINA_PASSWORD: SECRETS.marina,
    SEED_BRUNO_USERNAME: 'bruno',
    SEED_BRUNO_PASSWORD: SECRETS.bruno,
  }
  try {
    await assert.rejects(
      run(env, { log: () => {}, sleep: async () => {}, readyAttempts: 3, today: '2026-11-16' }),
      /indisponível após 3 tentativas/,
    )
  } finally {
    server.close()
  }
})
