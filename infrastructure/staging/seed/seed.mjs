#!/usr/bin/env node
// Seed fictício do staging (ADR-0044 §8 item 35). Node 22, sem dependências.
//
// Carrega os dados de `design/specs/09-dados-de-exemplo.md` pela API pública (OIDC + REST), com datas relativas a
// hoje. Nenhum SQL, nenhum endpoint interno. Dinheiro sempre em BigInt (centavos), nunca em float.
// Logs: só nomes de etapa, contagens e status HTTP. Nunca token, senha, e-mail ou valores.

import { pathToFileURL } from 'node:url'

// ---------------------------------------------------------------------------------------------------------------
// Datas relativas (puras)

const pad = (n) => String(n).padStart(2, '0')
export const isoOf = (y, m, d) => `${y}-${pad(m)}-${pad(d)}`
const parse = (iso) => iso.split('-').map(Number)

export function daysInMonth(year, month) {
  return new Date(Date.UTC(year, month, 0)).getUTCDate()
}

/** Soma meses a uma data ISO; o dia é limitado ao fim do mês de destino. */
export function addMonths(iso, n) {
  const [y, m, d] = parse(iso)
  const index = y * 12 + (m - 1) + n
  const year = Math.floor(index / 12)
  const month = (index % 12) + 1
  return isoOf(year, month, Math.min(d, daysInMonth(year, month)))
}

export function addDays(iso, n) {
  const [y, m, d] = parse(iso)
  const date = new Date(Date.UTC(y, m - 1, d + n))
  return isoOf(date.getUTCFullYear(), date.getUTCMonth() + 1, date.getUTCDate())
}

/** "Hoje" no fuso de negócio (ADR-0006 §10), como o backend calcula o mês e a fatura atuais. */
export function businessToday(now = new Date()) {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Sao_Paulo' }).format(now)
}

export const brDate = (iso) => iso.split('-').reverse().join('/')
export const yearMonth = (iso) => iso.slice(0, 7)

/** Primeiro dia do primeiro mês do histórico: 6 meses (5 anteriores + o corrente) terminando hoje. */
export function historyStart(today) {
  return `${addMonths(today, -5).slice(0, 8)}01`
}

// ---------------------------------------------------------------------------------------------------------------
// Dinheiro (centavos em BigInt)

/** "-1800,00" (modelo canônico do CSV, ADR-0009 §6: um separador seguido de 2 dígitos é inequívoco). */
export function csvAmount(cents) {
  const abs = cents < 0n ? -cents : cents
  return `${cents < 0n ? '-' : ''}${abs / 100n},${String(abs % 100n).padStart(2, '0')}`
}

/** "1800.00" (string decimal do `MoneyDto` da API). */
export function apiAmount(cents) {
  const abs = cents < 0n ? -cents : cents
  return `${cents < 0n ? '-' : ''}${abs / 100n}.${String(abs % 100n).padStart(2, '0')}`
}

const money = (cents) => ({ amount: apiAmount(cents), currency: 'BRL' })

/** Divide `total` em `n` partes inteiras; o resto vai para a primeira. A soma é sempre `total`. */
export function splitCents(total, n) {
  const count = BigInt(n)
  const base = total / count
  const parts = Array.from({ length: n }, () => base)
  parts[0] += total - base * count
  return parts
}

// ---------------------------------------------------------------------------------------------------------------
// Extrato da conta corrente (CSV canônico)

/** Despesa mensal com alimentação fora (centavos): abr..ago do exemplo; o mês corrente usa o valor de setembro. */
const DINING_BY_MONTH = [91200n, 100400n, 94800n, 102100n, 98000n, 128400n]
/** Energia varia um pouco por mês; o último valor fecha Moradia em R$ 1.986,32 com o aluguel. */
const ENERGY_BY_MONTH = [17850n, 19120n, 18210n, 20340n, 19480n, 18632n]
/** Comerciantes fictícios do exemplo: compras no mês e parte (em centavos de 128.400) de setembro. */
const DINING_MERCHANTS = [
  { name: 'Entrega Já Delivery', count: 9, share: 44700n },
  { name: 'Bistrô Lume', count: 5, share: 41280n },
  { name: 'Pizzaria Forno', count: 3, share: 23800n },
  { name: 'Café Grão', count: 8, share: 18620n },
]
const DINING_SHARE_TOTAL = 128400n

/** Dia da k-ésima compra de `count` espalhadas pelos 28 primeiros dias do mês. */
const spreadDay = (k, count) => 1 + Math.floor(((2 * k + 1) * 28) / (2 * count))

/**
 * Linhas do extrato do Banco Aurora, de 5 meses atrás até hoje (nada no futuro).
 * Retorna `{ date: 'yyyy-MM-dd', description, cents: bigint, id }` em ordem cronológica.
 */
export function statementLines(today) {
  const first = addMonths(`${today.slice(0, 8)}01`, -5)
  const raw = []
  for (let i = 0; i < 6; i++) {
    const [year, month] = parse(addMonths(first, i))
    const day = (d) => isoOf(year, month, Math.min(d, daysInMonth(year, month)))
    const add = (d, description, cents) => raw.push({ date: day(d), description, cents })

    add(5, 'Salário - Empresa Fictícia Ltda', 650000n)
    if (i === 3 || i === 5) add(12, 'Freelance - Projeto Exemplo', 30000n)
    add(5, 'Aluguel - Imobiliária Exemplo', -180000n)
    add(8, 'Companhia de Energia Fictícia', -ENERGY_BY_MONTH[i])
    add(10, 'Streaming de Vídeo Exemplo', -3990n)
    splitCents(29608n, 3).forEach((cents, k) => add([6, 14, 23][k], 'Mercado Bom Preço', -cents))
    add(15, 'Posto de Combustível Central', -15000n)
    add(20, 'Transporte por aplicativo Exemplo', -7000n)
    add(18, 'Papelaria Exemplo', -1990n)

    // Alimentação fora: o total do mês é repartido pelos comerciantes na proporção do exemplo, sem perder centavos.
    const total = DINING_BY_MONTH[i]
    const parts = DINING_MERCHANTS.map((m) => (total * m.share) / DINING_SHARE_TOTAL)
    parts[0] += total - parts.reduce((a, b) => a + b, 0n)
    DINING_MERCHANTS.forEach((merchant, m) => {
      splitCents(parts[m], merchant.count).forEach((cents, k) =>
        add(spreadDay(k, merchant.count), merchant.name, -cents),
      )
    })
  }
  const lines = raw.filter((l) => l.date <= today).sort((a, b) => (a.date < b.date ? -1 : a.date > b.date ? 1 : 0))
  const counters = new Map()
  return lines.map((l) => {
    const key = yearMonth(l.date).replace('-', '')
    const n = (counters.get(key) ?? 0) + 1
    counters.set(key, n)
    return { ...l, id: `SEED-${key}-${String(n).padStart(3, '0')}` }
  })
}

export const sumCents = (values) => values.reduce((total, v) => total + v, 0n)

export function statementCsv(lines) {
  const rows = lines.map((l) => [brDate(l.date), l.description, csvAmount(l.cents), l.id].join(';'))
  return ['data;descricao;valor;id', ...rows].join('\r\n') + '\r\n'
}

// ---------------------------------------------------------------------------------------------------------------
// Fatura do cartão (CSV de fatura, ADR-0009 §22: positivo = compra)

export const AURORA_CLOSING_DAY = 28

/** Início da fatura atual de um cartão que fecha no dia 28 (ADR-0025 §5: `[fechamento anterior, fechamento)`). */
export function cardPeriodStart(today, closingDay = AURORA_CLOSING_DAY) {
  const [year, month, day] = parse(today)
  const thisMonth = isoOf(year, month, closingDay)
  return day >= closingDay ? thisMonth : addMonths(thisMonth, -1)
}

/**
 * Fatura atual do Aurora Platinum: o notebook (parcela 3 de 10 × R$ 480,00, ADR-0025 §15: a compra é 2 meses antes
 * do início da fatura) e compras do ciclo, nunca depois de hoje.
 */
export function cardStatementLines(today) {
  const start = cardPeriodStart(today)
  const at = (offset) => {
    const date = addDays(start, offset)
    return date > today ? today : date
  }
  const purchases = [
    [1, 'Streaming de Vídeo Exemplo', 3990n],
    [2, 'Bistrô Lume', 8640n],
    [3, 'Entrega Já Delivery', 4990n],
    [5, 'Mercado Bom Preço', 14235n],
    [7, 'Posto de Combustível Central', 15000n],
    [9, 'Café Grão', 1870n],
  ]
  const lines = [
    {
      date: addMonths(start, -2),
      description: 'Loja Tech Fictícia · Notebook',
      cents: 48000n,
      installment: '3/10',
    },
    ...purchases.map(([offset, description, cents]) => ({ date: at(offset), description, cents, installment: '' })),
  ]
  return lines.map((l, i) => ({ ...l, id: `SEED-CARD-${String(i + 1).padStart(3, '0')}` }))
}

export function cardStatementCsv(lines) {
  const rows = lines.map((l) => [brDate(l.date), l.description, csvAmount(l.cents), l.installment, l.id].join(';'))
  return ['data;descricao;valor;parcela;id', ...rows].join('\r\n') + '\r\n'
}

// ---------------------------------------------------------------------------------------------------------------
// Ambiente e HTTP

export class SeedError extends Error {
  constructor(step, detail) {
    super(`${step}: ${detail}`)
    this.step = step
  }
}

const REQUIRED_ENV = [
  'SEED_API_BASE_URL',
  'SEED_TOKEN_URL',
  'SEED_CLIENT_ID',
  'SEED_CLIENT_SECRET',
  'SEED_MARINA_USERNAME',
  'SEED_MARINA_PASSWORD',
  'SEED_BRUNO_USERNAME',
  'SEED_BRUNO_PASSWORD',
]

export function readEnv(env) {
  const missing = REQUIRED_ENV.filter((name) => !env[name])
  if (missing.length > 0) throw new SeedError('env', `variáveis obrigatórias ausentes: ${missing.join(', ')}`)
  return {
    apiBase: env.SEED_API_BASE_URL.replace(/\/+$/, ''),
    tokenUrl: env.SEED_TOKEN_URL,
    clientId: env.SEED_CLIENT_ID,
    clientSecret: env.SEED_CLIENT_SECRET,
    marina: { username: env.SEED_MARINA_USERNAME, password: env.SEED_MARINA_PASSWORD },
    bruno: { username: env.SEED_BRUNO_USERNAME, password: env.SEED_BRUNO_PASSWORD },
  }
}

const sleepMs = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
const READY_STATUSES = new Set([502, 503, 504])

/**
 * Uma requisição. Com `retry`, repete em falha de conexão e em 502/503/504 (a API ainda subindo); sem ele, uma só
 * tentativa. Devolve `{ status, body }`; o corpo nunca é logado.
 */
async function call(ctx, step, url, init = {}, { retry = false } = {}) {
  const attempts = retry ? ctx.readyAttempts : 1
  let last = 'sem resposta'
  for (let attempt = 1; attempt <= attempts; attempt++) {
    try {
      const response = await ctx.fetch(url, { ...init, signal: AbortSignal.timeout(30_000) })
      if (!(retry && READY_STATUSES.has(response.status))) {
        const text = await response.text()
        let body = null
        try {
          body = text ? JSON.parse(text) : null
        } catch {
          body = null
        }
        return { status: response.status, body }
      }
      last = `HTTP ${response.status}`
    } catch (error) {
      last = `falha de conexão (${error?.cause?.code ?? error?.name ?? 'erro'})`
      if (!retry) throw new SeedError(step, last)
    }
    if (attempt < attempts) await ctx.sleep(ctx.readyDelayMs)
  }
  throw new SeedError(step, `serviço indisponível após ${attempts} tentativas (${last})`)
}

function fail(step, response) {
  const code = typeof response.body?.code === 'string' ? ` code=${response.body.code}` : ''
  throw new SeedError(step, `HTTP ${response.status}${code}`)
}

function ensure(step, response, ...ok) {
  ctxLog(step, response.status)
  if (!ok.includes(response.status)) fail(step, response)
  return response.body
}

let ctxLog = () => {}

async function fetchToken(ctx, step, { username, password }) {
  const form = new URLSearchParams({
    grant_type: 'password',
    client_id: ctx.env.clientId,
    client_secret: ctx.env.clientSecret,
    username,
    password,
    scope: 'openid',
  })
  const response = await call(
    ctx,
    step,
    ctx.env.tokenUrl,
    { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: form },
    { retry: true },
  )
  const body = ensure(step, response, 200)
  if (typeof body?.access_token !== 'string') throw new SeedError(step, 'resposta sem access_token')
  return body.access_token
}

const api = (ctx, step, token, method, path, { json, headers, body, retry } = {}) =>
  call(
    ctx,
    step,
    `${ctx.env.apiBase}${path}`,
    {
      method,
      headers: {
        authorization: `Bearer ${token}`,
        ...(json === undefined ? {} : { 'content-type': 'application/json' }),
        ...headers,
      },
      body: json === undefined ? body : JSON.stringify(json),
    },
    { retry },
  )

// ---------------------------------------------------------------------------------------------------------------
// Etapas do seed

async function provision(ctx, step, token) {
  const response = await api(ctx, step, token, 'POST', '/api/v1/users/me', { retry: true })
  const body = ensure(step, response, 200, 201)
  if (typeof body?.workspaceId !== 'string') throw new SeedError(step, 'resposta sem workspaceId')
  return body.workspaceId
}

async function listAccounts(ctx, step, token, workspaceId) {
  const response = await api(ctx, step, token, 'GET', `/api/v1/workspaces/${workspaceId}/accounts?includeArchived=true`)
  return ensure(step, response, 200).items ?? []
}

async function createAccount(ctx, token, ws, { name, type, institutionName, includedInTotal }) {
  const response = await api(ctx, `conta:${name}`, token, 'POST', `/api/v1/workspaces/${ws}/accounts`, {
    json: { name, type, institutionName, includedInTotal },
  })
  return ensure(`conta:${name}`, response, 201).id
}

/** Saldo inicial como `ADJUSTMENT` datado no início do histórico (mesmo mecanismo do BFF, ADR-0004 §4). */
async function openingBalance(ctx, token, ws, accountId, key, cents, date) {
  if (cents === 0n) return
  const step = `saldo:${key}`
  const response = await api(ctx, step, token, 'POST', `/api/v1/workspaces/${ws}/transactions`, {
    headers: { 'Idempotency-Key': `seed:opening:${key}` },
    json: {
      type: 'ADJUSTMENT',
      accountId,
      adjustmentDirection: cents > 0n ? 'INCREASE' : 'DECREASE',
      amount: money(cents < 0n ? -cents : cents),
      occurredOn: date,
      description: 'Saldo inicial',
      status: 'POSTED',
    },
  })
  ensure(step, response, 200, 201)
}

/** Upload → preview → confirmação → polling até o fim (ADR-0009 §2). */
async function importCsv(ctx, token, ws, label, target, csv, expectedLines) {
  const upload = `importação:${label}`
  const form = new FormData()
  form.append('file', new Blob([csv], { type: 'text/csv' }), `${label}.csv`)
  const query = 'accountId' in target ? `accountId=${target.accountId}` : `cardId=${target.cardId}`
  const created = ensure(
    upload,
    await api(ctx, upload, token, 'POST', `/api/v1/workspaces/${ws}/imports?${query}`, {
      headers: { 'Idempotency-Key': `seed:import:${label}` },
      body: form,
    }),
    200,
    201,
  )
  const lines = created.lines ?? {}
  ctxCounts(upload, `válidas=${lines.valid} inválidas=${lines.invalid} duplicadas=${lines.duplicate}`)
  if (created.status === 'PREVIEW') {
    if (lines.invalid > 0 || lines.valid !== expectedLines) {
      throw new SeedError(upload, `preview inesperado (válidas=${lines.valid}, inválidas=${lines.invalid})`)
    }
    ensure(
      `confirmação:${label}`,
      await api(ctx, `confirmação:${label}`, token, 'POST', `/api/v1/workspaces/${ws}/imports/${created.id}/confirm`),
      200,
      202,
    )
  }
  for (let i = 0; i < ctx.pollMax; i++) {
    const current = ensure(
      `acompanhamento:${label}`,
      await api(ctx, `acompanhamento:${label}`, token, 'GET', `/api/v1/workspaces/${ws}/imports/${created.id}`),
      200,
    )
    if (current.status === 'COMPLETED') {
      ctxCounts(upload, `importadas=${current.lines?.imported}`)
      return
    }
    if (['FAILED', 'CANCELLED', 'EXPIRED'].includes(current.status)) {
      throw new SeedError(upload, `terminou em ${current.status} (${current.failureReason ?? 'sem motivo'})`)
    }
    await ctx.sleep(ctx.pollMs)
  }
  throw new SeedError(upload, 'tempo esgotado esperando o processamento')
}

let ctxCounts = () => {}

async function createCard(ctx, token, ws, card) {
  const step = `cartão:${card.nickname}`
  const response = await api(ctx, step, token, 'POST', `/api/v1/workspaces/${ws}/cards`, {
    json: { ...card, limit: money(card.limit) },
  })
  return ensure(step, response, 201).id
}

async function createGoal(ctx, token, ws, goal) {
  const step = `objetivo:${goal.name}`
  const response = await api(ctx, step, token, 'POST', `/api/v1/workspaces/${ws}/goals`, {
    json: { ...goal, targetAmount: money(goal.targetAmount), plannedMonthly: money(goal.plannedMonthly) },
  })
  ensure(step, response, 201)
}

async function seedMarina(ctx, token, ws, today) {
  const start = historyStart(today)
  const lines = statementLines(today)
  const net = sumCents(lines.map((l) => l.cents))

  const aurora = await createAccount(ctx, token, ws, {
    name: 'Banco Aurora',
    type: 'CHECKING',
    institutionName: 'Banco Aurora',
    includedInTotal: true,
  })
  const norte = await createAccount(ctx, token, ws, {
    name: 'Banco Norte · Reserva',
    type: 'SAVINGS',
    institutionName: 'Banco Norte',
    includedInTotal: true,
  })
  const horizonte = await createAccount(ctx, token, ws, {
    name: 'Conta Horizonte',
    type: 'PAYMENT',
    institutionName: 'Horizonte',
    includedInTotal: true,
  })
  // Destino dedicado do objetivo de Lisboa (ADR-0034 §2.1: não pode ser poupança); fora do total para o saldo bater.
  const cofre = await createAccount(ctx, token, ws, {
    name: 'Cofre Viagem Lisboa',
    type: 'OTHER',
    institutionName: null,
    includedInTotal: false,
  })

  // Saldos do exemplo: Aurora R$ 6.240,35 (ajuste = alvo − soma do extrato), Norte 10.000, Horizonte 2.180, cofre 4.300.
  await openingBalance(ctx, token, ws, aurora, 'aurora', 624035n - net, start)
  await openingBalance(ctx, token, ws, norte, 'norte', 1000000n, start)
  await openingBalance(ctx, token, ws, horizonte, 'horizonte', 218000n, start)
  await openingBalance(ctx, token, ws, cofre, 'cofre', 430000n, start)

  await importCsv(ctx, token, ws, 'extrato-aurora', { accountId: aurora }, statementCsv(lines), lines.length)

  const auroraCard = await createCard(ctx, token, ws, {
    nickname: 'Aurora Platinum',
    brand: 'MASTERCARD',
    last4: '4821',
    institutionName: 'Banco Aurora',
    limit: 1200000n,
    closingDay: 28,
    dueDay: 5,
    defaultPaymentAccountId: aurora,
  })
  await createCard(ctx, token, ws, {
    nickname: 'Horizonte Gold',
    brand: 'VISA',
    last4: '0937',
    institutionName: 'Horizonte',
    limit: 600000n,
    closingDay: 5,
    dueDay: 12,
    defaultPaymentAccountId: horizonte,
  })
  const card = cardStatementLines(today)
  await importCsv(ctx, token, ws, 'fatura-aurora', { cardId: auroraCard }, cardStatementCsv(card), card.length)

  await createGoal(ctx, token, ws, {
    name: 'Reserva de emergência',
    type: 'EMERGENCY_RESERVE',
    targetAmount: 2515200n,
    plannedMonthly: 80000n,
    origin: { kind: 'MANUAL' },
    sourceAccountId: aurora,
  })
  await createGoal(ctx, token, ws, {
    name: 'Viagem para Lisboa',
    type: 'PURCHASE',
    targetAmount: 1200000n,
    plannedMonthly: 70000n,
    deadline: yearMonth(addMonths(today, 10)),
    origin: { kind: 'MANUAL' },
    sourceAccountId: aurora,
    destinationAccountId: cofre,
  })
}

/**
 * Executa o seed. `deps` existe para os testes (fetch, relógio, pausas e log injetáveis).
 * Sai com exceção em qualquer erro; o chamador converte em código de saída.
 */
export async function run(rawEnv, deps = {}) {
  const log = deps.log ?? ((line) => console.log(line))
  const ctx = {
    env: readEnv(rawEnv),
    fetch: deps.fetch ?? globalThis.fetch,
    sleep: deps.sleep ?? sleepMs,
    readyAttempts: deps.readyAttempts ?? 40,
    readyDelayMs: deps.readyDelayMs ?? 3000,
    pollMs: deps.pollMs ?? 1500,
    pollMax: deps.pollMax ?? 120,
  }
  ctxLog = (step, status) => log(`[seed] ${step}: HTTP ${status}`)
  ctxCounts = (step, text) => log(`[seed] ${step}: ${text}`)
  const today = deps.today ?? businessToday()

  const marinaToken = await fetchToken(ctx, 'token:marina', ctx.env.marina)
  const marinaWs = await provision(ctx, 'provisionamento:marina', marinaToken)
  const existing = await listAccounts(ctx, 'contas:marina', marinaToken, marinaWs)
  if (existing.length > 0) {
    log(`[seed] marina: já possui ${existing.length} conta(s); seed pulado (para recomeçar, use o reset do staging)`)
  } else {
    await seedMarina(ctx, marinaToken, marinaWs, today)
    log('[seed] marina: dados carregados')
  }

  const brunoToken = await fetchToken(ctx, 'token:bruno', ctx.env.bruno)
  const brunoWs = await provision(ctx, 'provisionamento:bruno', brunoToken)
  const brunoAccounts = await listAccounts(ctx, 'contas:bruno', brunoToken, brunoWs)
  log(`[seed] bruno: ${brunoAccounts.length} conta(s) (esperado 0)`)
  if (brunoWs === marinaWs) throw new SeedError('isolamento', 'Marina e Bruno compartilham o mesmo Workspace')

  // Isolamento: o Workspace da Marina responde como inexistente para o token do Bruno (ADR-0003).
  const probe = await api(ctx, 'isolamento', brunoToken, 'GET', `/api/v1/workspaces/${marinaWs}/accounts`)
  ctxLog('isolamento:bruno→marina', probe.status)
  if (probe.status !== 404) throw new SeedError('isolamento', `esperado 404, recebido ${probe.status}`)

  log('[seed] concluído')
}

const isMain = process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href
if (isMain) {
  run(process.env).catch((error) => {
    const step = error instanceof SeedError ? error.message : `erro inesperado (${error?.name ?? 'Error'})`
    console.error(`[seed] ERRO ${step}`)
    process.exit(1)
  })
}
