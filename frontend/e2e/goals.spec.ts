import { writeFileSync } from 'node:fs'
import type { Page } from '@playwright/test'
import { expect, test } from './support/test'

const CHECKING = 'Conta Objetivos E2E'
const RESERVE = 'Reserva Objetivos E2E'

interface Ym { year: number; month: number }

/** "Hoje" no fuso de negócio (ADR-0006 §10), como o backend calcula o mês corrente. */
function businessToday(): Ym {
  const [year, month] = new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Sao_Paulo', year: 'numeric', month: '2-digit' })
    .format(new Date())
    .split('-')
    .map(Number) as [number, number]
  return { year, month }
}

function shift({ year, month }: Ym, by: number): Ym {
  const index = year * 12 + (month - 1) + by
  return { year: Math.floor(index / 12), month: (index % 12) + 1 }
}

const SHORT = ['jan', 'fev', 'mar', 'abr', 'mai', 'jun', 'jul', 'ago', 'set', 'out', 'nov', 'dez']
const LONG = ['janeiro', 'fevereiro', 'março', 'abril', 'maio', 'junho', 'julho', 'agosto', 'setembro', 'outubro', 'novembro', 'dezembro']
/** "jul/2027", como a UI mostra a previsão e a simulação. */
const monthYear = ({ year, month }: Ym) => `${SHORT[month - 1]}/${year}`

/**
 * Objetivos ponta a ponta (M10, Sessão 7.2 parte D; ADR-0034/0035/0036/0037).
 *
 * ENTRADAS (datas relativas a hoje; M0 = mês corrente): extrato com salário de 5.000 e aluguel de 3.000 no dia 1 de
 * M-4..M0 numa conta corrente, e uma poupança fora do total aberta hoje com R$ 9.000,00 (a reserva).
 *
 * CONTAS ESPERADAS (goals-v1):
 *   - alvo sugerido da reserva = 6 × despesa média da janela do Health (M-4..M-1, 3.000/mês) = R$ 18.000,00;
 *   - "Já guardado" = soma das poupanças ativas = R$ 9.000,00 (50%);
 *   - plano de R$ 500,00/mês a partir de M+1: faltam 9.000 → 18 aportes → conclusão em M+18 (prévia Simulada);
 *   - aporte de R$ 1.000,00 hoje (corrente → poupança): guardado R$ 10.000,00, 56% (10.000 ÷ 18.000, HALF_UP),
 *     faltam R$ 8.000,00 → 16 aportes → previsão Estimada em M+16;
 *   - simulação de R$ 1.000,00/mês: 8 aportes → M+8; "Aplicar" troca só o plano → a previsão passa a M+8.
 *   Simular e aplicar não mexem em saldo, fluxo do mês nem Health: os três são comparados antes e depois.
 */
test('reserva a partir do Health: aporte, progresso exato, previsão, simulação e "Aplicar"', async ({ signedIn: page }, testInfo) => {
  test.setTimeout(300_000)
  const today = businessToday()

  const lines = [4, 3, 2, 1, 0].flatMap((back, i) => {
    const { year, month } = shift(today, -back)
    const day = `01/${String(month).padStart(2, '0')}/${year}`
    return [`${day};Salário Empresa Fictícia;5.000,00;s${i}`, `${day};Aluguel Casa;-3.000,00;a${i}`]
  })
  const csv = testInfo.outputPath('extrato-objetivos.csv')
  writeFileSync(csv, ['data;descricao;valor;id', ...lines].join('\n'), 'utf8')

  await page.goto('/import')
  await page.getByRole('main').getByRole('link', { name: 'Adicionar conta' }).click()
  await page.getByLabel('Nome da conta').fill(CHECKING)
  await page.getByRole('button', { name: 'Criar e continuar' }).click()
  await expect(page).toHaveURL(/\/import\?accountId=/)
  await page.locator('input[type="file"]').setInputFiles(csv)
  await page.getByRole('button', { name: 'Continuar' }).click()
  await page.getByRole('button', { name: `Importar ${lines.length} movimentações` }).click()
  await expect(page.getByRole('heading', { name: `${lines.length} movimentações importadas` })).toBeVisible({ timeout: 60_000 })

  await page.goto('/accounts/new')
  await page.getByLabel('Nome da conta').fill(RESERVE)
  await page.getByLabel('Tipo').selectOption({ label: 'Poupança' })
  await page.getByRole('switch', { name: 'Incluir no saldo total' }).click()
  await page.getByLabel('Saldo de hoje').fill('9.000,00')
  await page.getByLabel('Positivo').check()
  await page.getByRole('button', { name: 'Criar conta' }).click()
  await expect(page).toHaveURL(/\/accounts$/)

  // Health → "Criar objetivo de reserva" (adendo de 2026-11-06 ao ADR-0020), assim que o passo da reserva aparece.
  const create = page.getByRole('main').getByRole('link', { name: 'Criar objetivo de reserva' })
  await expectAfterRefresh(page, '/home/health', async () => {
    await expect(create).toBeVisible({ timeout: 1_000 })
  })
  await create.click()
  await expect(page).toHaveURL(/\/goals\/new\?type=EMERGENCY_RESERVE&origin=HEALTH$/)

  const main = page.getByRole('main')
  await expect(main).toContainText('Criado a partir da sua Saúde financeira')
  await expect(main.getByRole('radio', { name: /Reserva de emergência/ })).toBeChecked()
  await expect(main.getByLabel('Valor alvo')).toHaveValue('18.000,00', { timeout: 30_000 })
  await expect(main).toContainText('Sugestão: R$ 18.000,00, 6 meses da sua despesa média.')
  await expect(main.getByText('Já guardado').locator('..')).toContainText('R$ 9.000,00')
  await main.getByLabel('Aporte mensal planejado').fill('500,00')
  await main.getByLabel(/Conta de origem dos aportes/).selectOption({ label: CHECKING })
  const preview = main.getByRole('complementary', { name: 'Previsão' })
  await expect(preview).toContainText('Simulado')
  await expect(preview).toContainText(monthYear(shift(today, 18)), { timeout: 15_000 })
  await expect(preview.getByRole('definition').first()).toHaveText('18')
  await expect(preview).toContainText(monthYear(shift(today, 1)))
  await main.getByRole('button', { name: 'Criar objetivo' }).click()

  // D-GoalCreated: lista com o novo em primeiro, "Novo" e o toast.
  await expect(page).toHaveURL(/\/goals\?created=/)
  await expect(page.getByRole('status').filter({ hasText: 'Objetivo criado.' }))
    .toContainText(`O primeiro aporte está previsto para ${LONG[shift(today, 1).month - 1]}.`)
  const card = page.getByRole('article', { name: 'Reserva de emergência' })
  await expect(card).toContainText('Novo')
  await expect(card).toContainText('R$ 9.000,00')
  await expect(card).toContainText('de R$ 18.000,00')
  await expect(card).toContainText('50% concluído')
  await card.getByRole('link', { name: 'Reserva de emergência' }).click()
  await expect(page).toHaveURL(/\/goals\/[0-9a-f-]{36}$/)
  const goalUrl = page.url()

  // Aporte: uma TRANSFER corrente → poupança, criada pelo backend.
  await page.getByRole('button', { name: 'Registrar aporte' }).click()
  const dialog = page.getByRole('dialog', { name: 'Registrar aporte' })
  await expect(dialog.getByLabel('Conta de origem')).toHaveValue(/.+/)
  await expect(dialog.getByLabel('Conta da reserva').locator('option:checked')).toHaveText(RESERVE)
  await dialog.getByLabel('Valor').fill('1.000,00')
  const contributedAt = Date.now()
  await dialog.getByRole('button', { name: 'Registrar aporte' }).click()
  await expect(page.getByText('Aporte de R$ 1.000,00 registrado. O progresso já considera este valor.')).toBeVisible()
  await expect(dialog).toBeHidden()

  const progress = page.getByRole('main').getByRole('heading', { name: 'Progresso' }).locator('..')
  await expect(progress).toContainText('R$ 10.000,00')
  await expect(definition(progress, 'Concluído')).toHaveText('56%')
  await expect(definition(progress, 'Faltam')).toHaveText('R$ 8.000,00')
  await expect(page.getByRole('main').getByRole('heading', { name: 'Aportes', exact: true }).locator('..')).toContainText('R$ 1.000,00')

  // Previsão Estimada (precisa do Forecast pronto, ADR-0036 §1): recarrega até aparecer.
  await expectAfterRefresh(page, goalUrl, async () => {
    await expect(definition(page.getByRole('main').getByRole('heading', { name: 'Progresso' }).locator('..'), 'Previsão'))
      .toContainText(monthYear(shift(today, 16)), { timeout: 1_000 })
  })

  // Nada além do plano muda daqui em diante: saldo, fluxo do mês e Health antes da simulação. Espera o Health
  // recalcular com o aporte (o recálculo é assíncrono, ADR-0018) para comparar fotos estáveis.
  await expect(async () => {
    const health = await (await page.request.get('/api/bff/health/summary')).json() as { freshness: { state: string; computedAt: string | null } }
    expect(health.freshness.state).toBe('UP_TO_DATE')
    expect(Date.parse(health.freshness.computedAt ?? '') >= contributedAt - 5_000).toBe(true)
  }).toPass({ timeout: 90_000, intervals: [1_000, 2_000, 3_000] })
  const before = await snapshot(page)

  const sim = page.getByRole('main').getByRole('region', { name: 'Simular aporte' })
  await expect(sim).toContainText('Simulado')
  await sim.getByLabel('Outro valor por mês').fill('1.000,00')
  await sim.getByRole('button', { name: 'Simular' }).click()
  await expect(sim.getByRole('button', { name: /^R\$\s1\.000,00\/mês/ })).toHaveAttribute('aria-pressed', 'true')
  await expect(sim).toContainText(monthYear(shift(today, 8)))
  await expect(sim).toContainText('8 aportes a partir de')
  expect(await snapshot(page)).toEqual(before)

  await sim.getByRole('button', { name: /^Aplicar R\$\s1\.000,00\/mês ao objetivo$/ }).click()
  await sim.getByRole('group', { name: /Trocar o aporte planejado/ }).getByRole('button', { name: 'Confirmar' }).click()
  await expect(page.getByText('Plano atualizado: R$ 1.000,00 por mês. A previsão já considera o novo valor.')).toBeVisible()
  await page.reload()
  const after = page.getByRole('main').getByRole('heading', { name: 'Progresso' }).locator('..')
  await expect(definition(after, 'Aporte planejado')).toHaveText('R$ 1.000,00/mês')
  await expect(definition(after, 'Previsão')).toContainText(monthYear(shift(today, 8)))
  await expect(definition(after, 'Faltam')).toHaveText('R$ 8.000,00')
  expect(await snapshot(page)).toEqual(before)

  // Lista, M-More, Home (ação rápida) e as 5 larguras sem rolagem horizontal.
  await page.goto('/more')
  await expect(page.getByRole('main').getByRole('link', { name: 'Objetivos' })).toHaveAttribute('href', '/goals')
  await page.goto('/home')
  await expect(page.getByRole('region', { name: 'Ações rápidas' }).getByRole('link', { name: /Criar objetivo/ }))
    .toHaveAttribute('href', '/goals/new')
  for (const width of [375, 768, 1024, 1280, 1440]) {
    await page.setViewportSize({ width, height: 900 })
    for (const path of ['/goals', goalUrl.replace(/^https?:\/\/[^/]+/, ''), '/goals/new']) {
      await page.goto(path)
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
      expect(overflow, `${path} em ${width}px`).toBeLessThanOrEqual(0)
    }
  }
})

/** `<dd>` de um termo dentro de um escopo (dl do D-GoalDetail). */
function definition(scope: ReturnType<Page['getByRole']>, term: string) {
  return scope.locator('dt', { hasText: term }).locator('xpath=following-sibling::dd[1]')
}

/** Fatos que a simulação e o "Aplicar" não podem mudar: saldos e fluxo do Overview e o resultado do Health. */
async function snapshot(page: Page) {
  const overview = await (await page.request.get('/api/bff/overview')).json() as {
    summary: unknown
    cashFlow: unknown
    accounts: { id: string; balance: unknown }[]
  }
  const health = await (await page.request.get('/api/bff/health/summary')).json() as {
    score: unknown
    dimensions: { dimension: string; score: unknown }[]
  }
  return {
    summary: overview.summary,
    cashFlow: overview.cashFlow,
    balances: overview.accounts.map((a) => [a.id, a.balance]),
    score: health.score,
    dimensions: health.dimensions.map((d) => [d.dimension, d.score]),
  }
}

async function expectAfterRefresh(page: Page, path: string, assertion: () => Promise<void>) {
  await page.goto(path)
  await expect(async () => {
    await page.reload()
    await assertion()
  }).toPass({ timeout: 120_000, intervals: [1_000, 2_000, 3_000] })
}
