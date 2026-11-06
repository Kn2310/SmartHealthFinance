import { writeFileSync } from 'node:fs'
import type { Page } from '@playwright/test'
import { expect, test } from './support/test'
import { brl } from './support/money'

const ACCOUNT = 'Conta Forecast E2E'
const CARD = { nickname: 'Cartão Forecast E2E', last4: '6420' }
const RENT_DAY = 7
const SALARY_DAY = 5
const CLOSING_DAY = 28
/** O comerciante exibido é o nome normalizado, sem a palavra de categoria ("Aluguel", "Salário"). */
const RENT_LABEL = /Imobili[aá]ria/i
const SALARY_LABEL = /Empresa Fict/i
const DUE_DAY = 5

/**
 * Forecast ponta a ponta (M9 parte D, ADR-0033 §7): um histórico com recorrências determinadas gera "Próximos eventos",
 * a projeção do mês e a estimativa da próxima fatura, com os valores exatos do `forecast-v1` (ADR-0030 §3); depois
 * confirmar, desfazer e ignorar uma recorrência produz o efeito esperado.
 *
 * ENTRADAS (datas relativas a hoje, T = hoje no fuso de negócio, d = dia de T; M-k = k meses antes do mês de T):
 *   conta   Salário Empresa Fictícia +6.500,00 no dia 5, e Aluguel Imobiliária Fictícia -1.800,00 no dia 7, em M-6..M-1
 *           (1 ocorrência por mês, valores fixos: recorrências HIGH, 6 meses)
 *           Mercado Bairro Fictício -220,00 e -180,00 no dia 1 de M-6..M0 (2 por mês: nunca recorrente)
 *           Padaria Esquina Fictícia no dia 28 de M-6..M-1: 90, 210, 130, 170, 110, 190 (variam além de 10%: não recorrente)
 *   cartão  fecha 28, vence 5. Livraria Fictícia no dia 1 de M-3..M0: 140, 260, 180, 220 (não recorrente)
 *           Bistrô Fictício 90,00 hoje (fatura aberta) e Móveis Fictícios 150,00 parcela 2/4 (compra de 600,00 um mês antes)
 *
 * REGRAS (ADR-0030 §3, ADR-0031 §11) e a conta de cada valor:
 *   1. Séries elegíveis: Aluguel (despesa, dia 7, 1.800,00, faixa 1.800–1.800) e Salário (receita, dia 5, 6.500,00).
 *      Nenhum comerciante do variável é série (ver acima).
 *   2. Próximos eventos (§3.3): para cada série, a data esperada de cada mês de M0 em diante, no dia da série (limitado
 *      ao fim do mês), com hoje <= data <= hoje + 30. Nenhuma série tem ocorrência em M0, então M0 vale se dia >= d.
 *      Cada evento é "≈ R$ 1.800" (aluguel) ou "≈ R$ 6.500" (salário), Estimado, faixa igual ao valor.
 *   3. Projeção do mês (§3.1) = realizado + recorrentes pendentes + variável restante:
 *      realizado = Mercado 220+180 + Livraria M0 220 + Bistrô 90 = R$ 710,00 (a compra parcelada é de M-1: fora de M0).
 *      pendente  = aluguel 1.800 se o dia 7 vier depois de hoje (d < 7), senão 0.
 *      variável  = despesa fora das séries nos dias > d, em cada um dos 5 meses de base M-5..M-1. O único gasto depois
 *                  do dia 1 é a Padaria do dia 28: com d < 28 as amostras são 210, 130, 170, 110, 190 -> mediana 170,
 *                  faixa 110–210; com d >= 28, 0 em todos os meses. A compra parcelada (dia d do mês anterior) nunca
 *                  entra: o filtro é dia > d.
 *      central = 710 + pendente + mediana; faixa = 710 + pendente + [mín, máx] (somar estimativas soma mínimos e máximos).
 *   4. Próxima fatura do cartão (§3.5): comprometido = parcela 3/4 = R$ 150,00 (FACT). Novas compras = mediana das 3
 *      últimas faturas fechadas, sem parcelas: com d < 28 as fechadas são M-3, M-2, M-1 (140, 260, 180: mediana 180,
 *      faixa 140–260); com d >= 28 entra a de M0 e sai a de M-3 (260, 180, 220: mediana 220, faixa 180–260).
 *      total = 150 + novas compras; faixa = 150 + [mín, máx].
 *   5. Próxima receita (§3.2): Salário, ≈ R$ 6.500.
 *   6. Fatura aberta (§3.5): ver `openInvoice` — parcial (fato) e "≈ no fechamento" ao lado, no evento de fechamento.
 *   Depois de ignorar o aluguel, ele sai das séries elegíveis: some dos eventos e deixa de ser "pendente" na projeção,
 *   mas passa a contar como gasto variável (dia 7 > d), com 1.800 em cada mês de base. Como o valor é constante, mediana e
 *   faixa sobem os mesmos 1.800 e a projeção do mês fica igual (efeito do modelo, conferido em `monthProjection`).
 */

interface Ymd { year: number; month: number; day: number }

function businessToday(): Ymd {
  const [year, month, day] = new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Sao_Paulo', year: 'numeric',
    month: '2-digit', day: '2-digit' }).format(new Date()).split('-').map(Number) as [number, number, number]
  return { year, month, day }
}

/** O mês `back` meses antes (ou depois, se negativo) do mês de `from`. */
function shift({ year, month }: { year: number; month: number }, back: number) {
  const index = year * 12 + (month - 1) - back
  return { year: Math.floor(index / 12), month: (index % 12) + 1 }
}

const lengthOf = ({ year, month }: { year: number; month: number }) => new Date(year, month, 0).getDate()
const pad = (n: number) => String(n).padStart(2, '0')
const br = (m: { year: number; month: number }, day: number) => `${pad(day)}/${pad(m.month)}/${m.year}`
const money = (value: number) => value.toFixed(2).replace('.', ',')
/** "R$ 2.680" (estimativa sem centavos, como a UI). */
const whole = (value: bigint) => brl(value * 100n).replace(/,00$/, '')
const spoken = (central: bigint, low: bigint, high: bigint) =>
  `aproximadamente ${whole(central)}, entre ${whole(low)} e ${whole(high)}, estimado`

/** Eventos esperados de uma série mensal nos 30 dias (regra 2). */
function occurrencesIn30Days(today: Ymd, day: number): number {
  const end = new Date(Date.UTC(today.year, today.month - 1, today.day + 30))
  let count = 0
  for (let back = 0; back >= -2; back--) {
    const m = shift(today, back)
    const date = new Date(Date.UTC(m.year, m.month - 1, Math.min(day, lengthOf(m))))
    const start = new Date(Date.UTC(today.year, today.month - 1, today.day))
    if (date >= start && date <= end) count++
  }
  return count
}

const median = (values: bigint[]) => [...values].sort((a, b) => (a < b ? -1 : a > b ? 1 : 0))[Math.floor(values.length / 2)]!

/**
 * Projeção do mês (regra 3). `rentIgnored`: sem ser série elegível o aluguel deixa de ser pendente e passa a contar no
 * variável (só entra o que cai depois de hoje: dia 7 > d), então cada mês de base ganha 1.800 além da Padaria.
 */
function monthProjection(today: Ymd, rentIgnored: boolean) {
  const realized = 710n
  const pending = !rentIgnored && RENT_DAY > today.day ? 1800n : 0n
  const rentInVariable = rentIgnored && RENT_DAY > today.day ? 1800n : 0n
  const samples = [210n, 130n, 170n, 110n, 190n].map((padaria) => rentInVariable + (today.day < CLOSING_DAY ? padaria : 0n))
  const sorted = [...samples].sort((a, b) => (a < b ? -1 : a > b ? 1 : 0))
  return {
    central: realized + pending + median(samples),
    low: realized + pending + sorted[0]!,
    high: realized + pending + sorted.at(-1)!,
  }
}

function nextInvoice(today: Ymd) {
  const committed = 150n
  const base = today.day < CLOSING_DAY ? { central: 180n, low: 140n, high: 260n } : { central: 220n, low: 180n, high: 260n }
  return { committed, newPurchases: base.central, total: committed + base.central,
    low: committed + base.low, high: committed + base.high }
}

/** Dias entre duas datas (UTC, sem horário de verão). */
const daysBetween = (a: Date, b: Date) => Math.round((b.getTime() - a.getTime()) / 86_400_000)

/**
 * Fatura aberta (regra 6): parcial = 90 (Bistrô) + 150 (parcela 2/4) + 220 (Livraria de M0, só se d < 28: a fatura aberta
 * começou dia 28 do mês anterior). "≈ no fechamento" = parcial + mediana das compras das 3 últimas faturas fechadas depois
 * do dia equivalente a hoje: início do período atual + decorridos + 1. Todas as compras de base são no dia 1 de cada
 * mês, então contam só se o dia 1 daquela fatura é >= esse corte.
 */
function openInvoice(today: Ymd) {
  const partial = BigInt(240 + (today.day < CLOSING_DAY ? 220 : 0))
  const start = shift(today, today.day >= CLOSING_DAY ? 0 : 1)
  const elapsed = daysBetween(new Date(Date.UTC(start.year, start.month - 1, CLOSING_DAY)),
    new Date(Date.UTC(today.year, today.month - 1, today.day)))
  const amounts: Record<number, number> = { 3: 140, 2: 260, 1: 180, 0: 220 } // Livraria por M-k
  const base = today.day < CLOSING_DAY ? [3, 2, 1] : [2, 1, 0]
  const samples = base.map((back) => {
    const closingMonth = shift(today, back)
    const prev = shift(closingMonth, 1)
    const cut = Date.UTC(prev.year, prev.month - 1, CLOSING_DAY + elapsed + 1)
    return Date.UTC(closingMonth.year, closingMonth.month - 1, 1) >= cut ? BigInt(amounts[back]!) : 0n
  }).sort((a, b) => (a < b ? -1 : a > b ? 1 : 0))
  return { partial, central: partial + samples[1]!, low: partial + samples[0]!, high: partial + samples[2]! }
}

test('histórico com recorrências → próximos eventos, projeção do mês e próxima fatura; confirmar, desfazer e ignorar', async (
  { signedIn: page }, testInfo) => {
  test.setTimeout(600_000)
  const today = businessToday()
  const expectedRent = occurrencesIn30Days(today, RENT_DAY)
  const expectedSalary = occurrencesIn30Days(today, SALARY_DAY)

  // --- Extrato da conta (CSV canônico: negativo = saída) ---
  const paddies = [90, 210, 130, 170, 110, 190] // Padaria, de M-6 a M-1
  const lines: string[] = []
  for (let back = 6; back >= 1; back--) {
    const m = shift(today, back)
    lines.push(`${br(m, SALARY_DAY)};Salário Empresa Fictícia;6.500,00;sal${back}`)
    lines.push(`${br(m, RENT_DAY)};Aluguel Imobiliária Fictícia;-1.800,00;alu${back}`)
    lines.push(`${br(m, 28)};Padaria Esquina Fictícia;-${money(paddies[6 - back]!)};pad${back}`)
  }
  for (let back = 6; back >= 0; back--) {
    const m = shift(today, back)
    lines.push(`${br(m, 1)};Mercado Bairro Fictício;-220,00;mera${back}`)
    lines.push(`${br(m, 1)};Mercado Bairro Fictício;-180,00;merb${back}`)
  }
  const statement = testInfo.outputPath('extrato-forecast.csv')
  writeFileSync(statement, ['data;descricao;valor;id', ...lines].join('\n'), 'utf8')

  await page.goto('/import')
  await page.getByRole('main').getByRole('link', { name: 'Adicionar conta' }).click()
  await page.getByLabel('Nome da conta').fill(ACCOUNT)
  await page.getByRole('button', { name: 'Criar e continuar' }).click()
  await expect(page).toHaveURL(/\/import\?accountId=/)
  await page.locator('input[type="file"]').setInputFiles(statement)
  await page.getByRole('button', { name: 'Continuar' }).click()
  await page.getByRole('button', { name: `Importar ${lines.length} movimentações` }).click()
  await expect(page.getByRole('heading', { name: `${lines.length} movimentações importadas` })).toBeVisible({
    timeout: 60_000,
  })

  // --- Cartão e fatura (CSV de fatura: positivo = compra, coluna parcela) ---
  await page.goto('/cards/new?from=cards')
  await page.getByLabel('Apelido').fill(CARD.nickname)
  await page.getByLabel('Bandeira').selectOption('VISA')
  await page.getByLabel('4 últimos dígitos').fill(CARD.last4)
  await page.getByLabel('Limite').fill('10.000,00')
  await page.getByLabel('Dia de fechamento').fill(String(CLOSING_DAY))
  await page.getByLabel('Dia de vencimento').fill(String(DUE_DAY))
  await page.getByLabel('Conta que paga a fatura (opcional)').selectOption({ label: ACCOUNT })
  await page.getByRole('button', { name: 'Cadastrar cartão' }).click()
  await expect(page).toHaveURL(/\/cards\/[0-9a-f-]{36}$/)
  const cardId = page.url().split('/').pop()!

  const shops = [140, 260, 180, 220] // Livraria, de M-3 a M0
  const cardLines = shops.map((value, i) =>
    `${br(shift(today, 3 - i), 1)};Livraria Fictícia;${money(value)};;liv${3 - i}`)
  cardLines.push(`${br(today, today.day)};Bistrô Fictício;90,00;;bis0`)
  cardLines.push(`${br(today, today.day)};Móveis Fictícios;150,00;2/4;mov0`)
  const invoice = testInfo.outputPath('fatura-forecast.csv')
  writeFileSync(invoice, ['data;descricao;valor;parcela;id', ...cardLines].join('\n'), 'utf8')

  await page.goto('/import')
  await page.getByRole('radio', { name: new RegExp(CARD.nickname) }).check()
  await page.locator('input[type="file"]').setInputFiles(invoice)
  await page.getByRole('button', { name: 'Continuar' }).click()
  await page.getByRole('button', { name: `Importar ${cardLines.length} movimentações` }).click()
  await expect(page.getByRole('heading', { name: `${cardLines.length} movimentações importadas` })).toBeVisible({
    timeout: 60_000,
  })

  // --- Home: Próximos eventos (regra 2) ---
  const events = page.getByRole('region', { name: 'Próximos eventos' })
  await expectAfterRefresh(page, '/home', async () => {
    await expect(events.getByRole('listitem').filter({ hasText: RENT_LABEL })).toHaveCount(expectedRent, { timeout: 1_000 })
    await expect(events.getByRole('listitem').filter({ hasText: SALARY_LABEL })).toHaveCount(expectedSalary, { timeout: 1_000 })
  })
  for (const item of await events.getByRole('listitem').filter({ hasText: RENT_LABEL }).all()) {
    await expect(item).toContainText(spoken(1800n, 1800n, 1800n))
    await expect(item).toContainText('Estimado')
    await expect(item).toContainText('Recorrente')
  }
  for (const item of await events.getByRole('listitem').filter({ hasText: SALARY_LABEL }).all()) {
    await expect(item).toContainText(spoken(6500n, 6500n, 6500n))
    await expect(item).toContainText('Estimado')
  }
  // Os eventos de fatura continuam sendo fatos (Parcial ou Confirmado); o "≈ no fechamento" vem ao lado do parcial.
  const invoiceEvents = events.getByRole('listitem').filter({ hasText: /fatura/i })
  expect(await invoiceEvents.count()).toBeGreaterThan(0)
  for (const item of await invoiceEvents.all()) await expect(item).toContainText(/Parcial|Confirmado/)
  const closing = openInvoice(today)
  const closingEvent = events.getByRole('listitem').filter({ hasText: 'Fechamento da fatura' })
  if (await closingEvent.count() > 0) {
    await expect(closingEvent).toContainText(brl(closing.partial * 100n))
    await expect(closingEvent).toContainText('Parcial')
    await expect(closingEvent).toContainText('no fechamento')
    await expect(closingEvent).toContainText(spoken(closing.central, closing.low, closing.high))
  }

  // --- Análise › Gastos: projeção do mês (regra 3) ---
  const withRent = monthProjection(today, false)
  const projection = page.getByRole('region', { name: 'Projeção para o mês' })
  await page.goto('/analysis/spending')
  await expect(projection).toContainText(spoken(withRent.central, withRent.low, withRent.high))
  await expect(projection).toContainText('pelo seu padrão dos últimos 5 meses')
  await expect(projection).toContainText(`Realizado até agora: ${brl(71000n)}`)

  // --- Análise › Receitas: próxima receita (regra 5) ---
  await page.goto('/analysis/income')
  const nextIncome = page.getByRole('region', { name: 'Próxima receita prevista' })
  await expect(nextIncome).toContainText(SALARY_LABEL)
  await expect(nextIncome).toContainText(spoken(6500n, 6500n, 6500n))

  // --- Fatura: estimativa da próxima fatura (regra 4) ---
  const next = nextInvoice(today)
  await page.goto(`/cards/${cardId}/invoice`)
  const nextBlock = page.getByRole('region', { name: /Próxima fatura/ })
  await expect(nextBlock).toContainText('Já comprometido (parcelas)')
  await expect(nextBlock).toContainText(brl(next.committed * 100n))
  await expect(nextBlock).toContainText('Confirmado')
  await expect(nextBlock).toContainText('Novas compras pelo seu padrão')
  await expect(nextBlock).toContainText(`aproximadamente ${whole(next.newPurchases)}, estimado`)
  await expect(nextBlock).toContainText('Total estimado')
  await expect(nextBlock).toContainText(spoken(next.total, next.low, next.high))

  // --- /recurring: acesso, lista e confirmar ---
  await page.goto('/home')
  await page.getByRole('region', { name: 'Próximos eventos' }).getByRole('link', { name: 'Gerenciar recorrências' }).click()
  await expect(page).toHaveURL(/\/recurring$/)
  await expect(page.getByRole('heading', { level: 1, name: 'Recorrências' })).toBeVisible()
  const review = page.getByRole('region', { name: 'Para revisar' })
  const rent = review.getByRole('listitem').filter({ hasText: RENT_LABEL })
  await expect(rent).toContainText('Detectada')
  await expect(rent).toContainText(`todo dia ${RENT_DAY}`)
  await expect(rent).toContainText(ACCOUNT)
  await expect(rent).toContainText('confiança alta')
  await expect(rent).toContainText(spoken(1800n, 1800n, 1800n))

  await rent.getByRole('button', { name: /^Confirmar/ }).click()
  await expect(page.getByRole('status').filter({ hasText: 'Recorrência confirmada.' })).toBeVisible()
  await expect(page.getByRole('status').filter({ hasText: 'Atualizando estimativas…' })).toBeVisible()
  const confirmed = page.getByRole('region', { name: 'Confirmadas' })
  await expect(confirmed.getByRole('listitem').filter({ hasText: RENT_LABEL })).toBeVisible()
  await waitForForecast(page)
  await page.reload()
  await expect(page.getByRole('region', { name: 'Confirmadas' }).getByRole('listitem').filter({ hasText: RENT_LABEL })).toBeVisible()

  // --- Receitas: aba por URL ---
  await page.goto('/recurring?tab=incomes')
  await expect(page.getByRole('region', { name: 'Para revisar' }).getByRole('listitem').filter({ hasText: SALARY_LABEL }))
    .toContainText(spoken(6500n, 6500n, 6500n))

  // --- Ignorar com "Desfazer" (confirma de volta) ---
  await page.goto('/recurring')
  const row = page.getByRole('region', { name: 'Confirmadas' }).getByRole('listitem').filter({ hasText: RENT_LABEL })
  await row.getByRole('button', { name: /^Ignorar/ }).click()
  await expect(page.getByRole('status').filter({ hasText: 'Recorrência ignorada.' })).toBeVisible()
  await expect(page.getByRole('region', { name: 'Confirmadas' })).toHaveCount(0)
  await page.getByRole('button', { name: 'Desfazer' }).click()
  await expect(page.getByRole('region', { name: 'Confirmadas' }).getByRole('listitem').filter({ hasText: RENT_LABEL })).toBeVisible()

  // --- Ajustar: valor e dia, erro por campo e voltar ao detectado ---
  await page.getByRole('button', { name: /^Ajustar/ }).first().click()
  const drawer = page.getByRole('dialog', { name: 'Ajustar recorrência' })
  await drawer.getByLabel('Dia do mês').fill('32')
  await drawer.getByRole('button', { name: 'Salvar ajuste' }).click()
  await expect(drawer.getByText('Use um dia do mês de 1 a 31.')).toBeVisible()
  await drawer.getByLabel('Dia do mês').fill(String(RENT_DAY))
  await drawer.getByLabel('Valor esperado').fill('1.950,00')
  await drawer.getByRole('button', { name: 'Salvar ajuste' }).click()
  await expect(drawer).toBeHidden()
  const adjusted = page.getByRole('region', { name: 'Confirmadas' }).getByRole('listitem').filter({ hasText: RENT_LABEL })
  await expect(adjusted).toContainText('Ajustada por você')
  await expect(adjusted).toContainText(spoken(1950n, 1950n, 1950n))
  await adjusted.getByRole('button', { name: /^Ajustar/ }).click()
  await page.getByRole('dialog', { name: 'Ajustar recorrência' }).getByRole('button', { name: 'Voltar ao detectado' }).click()
  await expect(adjusted).toContainText('Detectada')
  await expect(adjusted).toContainText(spoken(1800n, 1800n, 1800n))

  // --- Ignorar de vez: o aluguel sai dos eventos e do pendente da projeção (regras 2 e 3) ---
  await adjusted.getByRole('button', { name: /^Ignorar/ }).click()
  await expect(page.getByRole('status').filter({ hasText: 'Recorrência ignorada.' })).toBeVisible()
  await waitForForecast(page)
  await page.reload()
  const ignored = page.getByText('Ignoradas').locator('xpath=ancestor::details')
  await ignored.locator('summary').click()
  await expect(ignored.getByRole('listitem').filter({ hasText: RENT_LABEL })).toBeVisible()

  const withoutRent = monthProjection(today, true)
  await page.goto('/home')
  await expect(page.getByRole('region', { name: 'Próximos eventos' }).getByRole('listitem').filter({ hasText: SALARY_LABEL }))
    .toHaveCount(expectedSalary)
  await expect(page.getByRole('region', { name: 'Próximos eventos' }).getByRole('listitem').filter({ hasText: RENT_LABEL }))
    .toHaveCount(0)
  await page.goto('/analysis/spending')
  await expect(page.getByRole('region', { name: 'Projeção para o mês' }))
    .toContainText(spoken(withoutRent.central, withoutRent.low, withoutRent.high))

  // --- M-More leva a /recurring ---
  await page.goto('/more')
  await page.getByRole('main').getByRole('link', { name: 'Recorrências' }).click()
  await expect(page).toHaveURL(/\/recurring$/)

  // --- Sem rolagem horizontal nas larguras do DS ---
  for (const width of [375, 768, 1024, 1280, 1440]) {
    await page.setViewportSize({ width, height: 900 })
    for (const path of ['/recurring', '/recurring?tab=incomes']) {
      await page.goto(path)
      await expect(page.getByRole('heading', { level: 1, name: 'Recorrências' })).toBeVisible()
      if (path.includes('incomes')) {
        await expect(page.getByRole('region', { name: 'Para revisar' }).getByRole('listitem').filter({ hasText: SALARY_LABEL })).toBeVisible()
      } else {
        // Despesas: só a ignorada, numa seção recolhida; abre para medir a lista inteira.
        await page.getByText('Ignoradas').click()
        await expect(page.getByRole('listitem').filter({ hasText: RENT_LABEL })).toBeVisible()
      }
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
      expect(overflow, `${path} em ${width}px`).toBeLessThanOrEqual(0)
    }
  }
})

/** Espera o Forecast terminar de recalcular depois de uma ação (o `status` fica RECALCULATING até lá). */
async function waitForForecast(page: Page) {
  await expect.poll(async () => {
    const response = await page.request.get('/api/bff/forecast/status')
    return response.ok() ? ((await response.json()) as { state: string }).state : 'ERROR'
  }, { timeout: 180_000, intervals: [1_000, 2_000, 3_000] }).toBe('UP_TO_DATE')
}

/** O Forecast é eventual (eventos + debounce): recarrega até a tela refletir o histórico importado. */
async function expectAfterRefresh(page: Page, path: string, assertion: () => Promise<void>) {
  await page.goto(path)
  await expect(async () => {
    await page.reload()
    await assertion()
  }).toPass({ timeout: 240_000, intervals: [2_000, 3_000, 5_000] })
}

