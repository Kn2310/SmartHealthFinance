import { writeFileSync } from 'node:fs'
import type { Page } from '@playwright/test'
import { expect, test } from './support/test'

const CHECKING = 'Conta Saúde E2E'
const RESERVE = 'Reserva Saúde E2E'

/** "Hoje" no fuso de negócio (ADR-0006 §10), como o backend calcula o mês corrente. */
function businessToday(): { year: number; month: number } {
  const [year, month] = new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Sao_Paulo', year: 'numeric', month: '2-digit' })
    .format(new Date())
    .split('-')
    .map(Number) as [number, number]
  return { year, month }
}

function shift({ year, month }: { year: number; month: number }, back: number) {
  const index = year * 12 + (month - 1) - back
  return { year: Math.floor(index / 12), month: (index % 12) + 1 }
}

const MONTHS = ['janeiro', 'fevereiro', 'março', 'abril', 'maio', 'junho', 'julho', 'agosto', 'setembro', 'outubro',
  'novembro', 'dezembro']

/**
 * Financial Health ponta a ponta (ADR-0017/0018/0019): extrato com 3 meses fechados e o corrente (salário de 5.000 e
 * aluguel de 3.000 no dia 1 de cada mês) e uma poupança fora do total aberta hoje com R$ 9.000,00. Valores exatos do
 * `health-v1`, que valem em qualquer dia do mês (inclusive o último):
 *   Cash Flow 100 (sobra de 40%, 3 de 3 meses positivos) · Spending 100 (gasto igual à média) · Reserve 80
 *   (9.000 ÷ 3.000 = 3,00 meses) · Consistency 100 → (2.500 + 1.500 + 1.600 + 1.000) ÷ 70 = 94.
 *   Mês anterior: a poupança ainda não tinha saldo → Reserve 0 → 5.000 ÷ 70 = 71 → variação +23.
 */
test('score e dimensões do health-v1 na Home, em /home/health e na Evolução', async ({ signedIn: page }, testInfo) => {
  test.setTimeout(240_000)
  const today = businessToday()
  const previousMonth = MONTHS[shift(today, 1).month - 1]!

  // Histórico pelo extrato CSV (datas relativas a hoje, o teste não depende da data).
  const lines = [3, 2, 1, 0].flatMap((back, i) => {
    const { year, month } = shift(today, back)
    const day = `01/${String(month).padStart(2, '0')}/${year}`
    return [`${day};Salário Empresa Fictícia;5.000,00;s${i}`, `${day};Aluguel Casa;-3.000,00;a${i}`]
  })
  const csv = testInfo.outputPath('extrato-saude.csv')
  writeFileSync(csv, ['data;descricao;valor;id', ...lines].join('\n'), 'utf8')

  await page.goto('/import')
  await page.getByRole('main').getByRole('link', { name: 'Adicionar conta' }).click()
  await page.getByLabel('Nome da conta').fill(CHECKING)
  await page.getByRole('button', { name: 'Criar e continuar' }).click()
  await expect(page).toHaveURL(/\/import\?accountId=/)
  await page.locator('input[type="file"]').setInputFiles(csv)
  await page.getByRole('button', { name: 'Continuar' }).click()
  await page.getByRole('button', { name: `Importar ${lines.length} movimentações` }).click()
  await expect(page.getByRole('heading', { name: `${lines.length} movimentações importadas` })).toBeVisible({
    timeout: 60_000,
  })

  // Poupança fora do total: é a reserva (ADR-0017 §2) e não entra no fluxo de caixa.
  await page.goto('/accounts/new')
  await page.getByLabel('Nome da conta').fill(RESERVE)
  await page.getByLabel('Tipo').selectOption({ label: 'Poupança' })
  await page.getByRole('switch', { name: 'Incluir no saldo total' }).click()
  await page.getByLabel('Saldo de hoje').fill('9.000,00')
  await page.getByLabel('Positivo').check()
  await page.getByRole('button', { name: 'Criar conta' }).click()
  await expect(page).toHaveURL(/\/accounts$/)

  // Home: Analytics → Health por eventos com debounce (ADR-0018): recarrega até o snapshot final.
  const card = page.getByRole('region', { name: 'Financial Health' })
  await expectAfterRefresh(page, '/home', async () => {
    await expect(card.getByRole('img', { name: 'Score 94 de 100' })).toBeVisible({ timeout: 1_000 })
    await expect(card).toContainText(`Seu score subiu 23 pontos desde ${previousMonth}`, { timeout: 1_000 })
  })
  const grid = card.getByRole('list', { name: 'Dimensões' })
  for (const [name, value] of [['Cash Flow', '100'], ['Spending', '100'], ['Credit', 'Em breve'], ['Debt', 'Em breve'],
    ['Reserve', '80'], ['Consistency', '100']] as const) {
    await expect(grid.getByRole('listitem').filter({ hasText: name })).toContainText(value)
  }

  // D-Health: dimensões com explicação e evidências; fatores e próximos passos.
  await card.getByRole('link', { name: 'Entender meu resultado' }).click()
  await expect(page).toHaveURL(/\/home\/health$/)
  const main = page.getByRole('main')
  await expect(main).toContainText('94 de 100')
  await expect(main).toContainText(`+23 pontos vs ${previousMonth}`)
  await expect(main).toContainText('Seu resultado combina 4 de 6 dimensões.')
  const reserve = main.getByRole('article').filter({ has: page.getByRole('heading', { name: 'Reserva' }) })
  await expect(reserve).toContainText('80/100')
  await expect(reserve).toContainText('Sua reserva cobre ≈ 3,0 meses da média de despesas (R$ 3.000,00/mês')
  await reserve.getByRole('button', { name: 'Detalhes' }).click()
  await expect(reserve).toContainText('R$ 9.000,00')
  await expect(main).toContainText('CSV')
  await expect(main.getByRole('link', { name: 'Ver contas' })).toHaveAttribute('href', '/accounts')
  await expect(main).toContainText('Faltam R$ 9.000,00 para 6 meses de despesas.')

  // "Como calculamos?": diálogo modal, Esc fecha e o foco volta ao gatilho.
  const trigger = main.getByRole('button', { name: 'Como calculamos?' })
  await trigger.click()
  const dialog = page.getByRole('dialog', { name: 'Como calculamos?' })
  await expect(dialog).toContainText('peso 25')
  await expect(dialog).toContainText('Investimentos')
  await page.keyboard.press('Escape')
  await expect(dialog).toBeHidden()
  await expect(trigger).toBeFocused()

  // Análise › Evolução: coluna de score do mesmo período (ADR-0019 §6).
  await page.goto('/analysis/evolution')
  const table = page.getByRole('table', { name: 'Mês a mês' })
  await expect(table.getByRole('row').last()).toContainText('94')

  // Responsivo (06-responsive): sem rolagem horizontal nas larguras de referência.
  for (const width of [375, 768, 1024, 1280, 1440]) {
    await page.setViewportSize({ width, height: 900 })
    for (const path of ['/home', '/home/health']) {
      await page.goto(path)
      await expect(page.getByRole('img', { name: 'Score 94 de 100' }).first()).toBeVisible()
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
      expect(overflow, `${path} em ${width}px`).toBeLessThanOrEqual(0)
    }
  }
})

async function expectAfterRefresh(page: Page, path: string, assertion: () => Promise<void>) {
  await page.goto(path)
  await expect(async () => {
    await page.reload()
    await assertion()
  }).toPass({ timeout: 90_000, intervals: [1_000, 2_000, 3_000] })
}
