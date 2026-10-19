import { writeFileSync } from 'node:fs'
import type { Page } from '@playwright/test'
import { expect, test } from './support/test'

const CHECKING = 'Conta Insights E2E'

const MONTHS = ['janeiro', 'fevereiro', 'março', 'abril', 'maio', 'junho', 'julho', 'agosto', 'setembro', 'outubro',
  'novembro', 'dezembro']

/** "Hoje" no fuso de negócio (ADR-0006 §10), como o backend. */
function businessToday(): { year: number; month: number; day: number } {
  const [year, month, day] = new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Sao_Paulo', year: 'numeric',
    month: '2-digit', day: '2-digit' }).format(new Date()).split('-').map(Number) as [number, number, number]
  return { year, month, day }
}

function shift({ year, month }: { year: number; month: number }, back: number) {
  const index = year * 12 + (month - 1) - back
  return { year: Math.floor(index / 12), month: (index % 12) + 1 }
}

/**
 * Período avaliado P (ADR-0021 §1): o mês anterior, fechado, do dia 1 ao 6; depois, o corrente — parcial, salvo no
 * último dia do mês.
 */
function evaluated() {
  const today = businessToday()
  const lastDay = new Date(today.year, today.month, 0).getDate()
  if (today.day < 7) return { ...shift(today, 1), partial: false }
  return { year: today.year, month: today.month, partial: today.day !== lastDay }
}

/**
 * Insights ponta a ponta (ADR-0021/0022/0023): extrato CSV com salário de 5.000 e "Restaurante Sabor" de 300 no dia 1
 * dos 5 meses antes de P; em P, o salário, 900 no restaurante e uma compra sem categoria de 600. Disparam, em qualquer
 * dia do mês: D1 alimentação fora +200% (≈ R$ 600), D2 gasto total +400% (≈ R$ 1.200), D4 a compra de 900 é 3,0× o
 * valor médio e D10 40% sem categoria. Verifica textos e valores exatos, detalhe, dispensa (e que o dispensado some),
 * Home, detalhe da transação, M-More e as 5 larguras.
 */
test('insights determinísticos: lista, detalhe, dispensa, Home e transação', async ({ signedIn: page }, testInfo) => {
  test.setTimeout(300_000)
  const p = evaluated()
  const monthName = MONTHS[p.month - 1]!
  const inPeriod = p.partial ? 'neste mês' : `em ${monthName}`

  const lines = [5, 4, 3, 2, 1, 0].flatMap((back, i) => {
    const { year, month } = shift(p, back)
    const day = `01/${String(month).padStart(2, '0')}/${year}`
    return [`${day};Salário Empresa Fictícia;5.000,00;s${i}`,
      `${day};Restaurante Sabor;-${back === 0 ? '900,00' : '300,00'};r${i}`]
  })
  const { year, month } = shift(p, 0)
  lines.push(`01/${String(month).padStart(2, '0')}/${year};Compra Loja Xyz;-600,00;x0`)
  const csv = testInfo.outputPath('extrato-insights.csv')
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

  // Analytics → Health → Insights por eventos com debounce (ADR-0022): recarrega até a geração.
  const spikeTitle = `Seus gastos com alimentação fora aumentaram 200% ${inPeriod}, cerca de R$ 600 a mais.`
  const totalTitle = `Seus gastos estão 400% acima do seu padrão ${inPeriod}, cerca de R$ 1.200 a mais.`
  const unusualTitle = 'Uma compra de R$ 900,00 em alimentação fora ficou 3,0 vezes acima do seu valor médio.'
  const uncategorizedTitle = `40% dos seus gastos ${p.partial ? 'deste mês' : `de ${monthName}`} estão sem categoria.`
  const main = page.getByRole('main')
  await expectAfterRefresh(page, '/insights', async () => {
    for (const title of [spikeTitle, totalTitle, unusualTitle, uncategorizedTitle]) {
      await expect(main.getByRole('link', { name: new RegExp(escape(title)) })).toBeVisible({ timeout: 1_000 })
    }
  })
  const chips = page.getByRole('group', { name: 'Filtrar insights' })
  await expect(chips.getByRole('button', { name: /^Todos/ })).toHaveAttribute('aria-pressed', 'true')
  await expect(chips.getByRole('button', { name: 'Cartões Em breve' })).toBeDisabled()
  await expect(main.getByRole('link', { name: new RegExp(`^Gastos, importante: ${escape(spikeTitle)}`) })).toBeVisible()
  await expect(main.getByRole('link', { name: new RegExp(`^Gastos, informativo: ${escape(uncategorizedTitle)}`) }))
    .toBeVisible()

  // Detalhe do D1: título = a frase, números exatos, mês em andamento, ações e "visto".
  await main.getByRole('link', { name: new RegExp(escape(spikeTitle)) }).click()
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(spikeTitle)
  await expect(main.getByText('Diferença', { exact: true }).locator('..')).toContainText('R$ 600,00')
  await expect(main.getByText('Variação', { exact: true }).locator('..')).toContainText('+200%')
  await expect(main).toContainText('Lançamentos de origem CSV')
  if (p.partial) await expect(main).toContainText(`${capitalize(monthName)} ainda não terminou`)
  await expect(main.getByRole('link', { name: 'Ver a transação' })).toHaveAttribute('href', /\/transactions\?/)
  await expect(main.getByRole('button', { name: 'Perguntar ao Copilot · Em breve' })).toBeDisabled()

  // Dispensar: confirmação inline; depois disso o insight some da lista (e nunca volta).
  await main.getByRole('button', { name: 'Dispensar insight' }).click()
  await main.getByRole('button', { name: 'Dispensar', exact: true }).click()
  await expect(main.getByText('Dispensado', { exact: true })).toBeVisible()
  await page.goto('/insights')
  await expect(main.getByRole('link', { name: new RegExp(escape(totalTitle)) })).toBeVisible()
  await expect(main.getByRole('link', { name: new RegExp(escape(spikeTitle)) })).toHaveCount(0)

  // Home: "Merece atenção" e "O que mudou?" sem o dispensado.
  await page.goto('/home')
  const changes = page.getByRole('region', { name: 'O que mudou' })
  await expect(changes).toContainText('Gastos do mês')
  await expect(changes).toContainText('+400% vs média de')
  await expect(changes).not.toContainText('Alimentação fora')
  await expect(page.getByRole('region', { name: 'Merece atenção' }).getByRole('link', { name: 'Ver todos' }))
    .toHaveAttribute('href', '/insights')

  // Detalhe da transação de 900: "Ver insight relacionado" leva à compra atípica (o D1 foi dispensado).
  await page.goto('/insights')
  await main.getByRole('link', { name: new RegExp(escape(unusualTitle)) }).click()
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(unusualTitle)
  const unusualUrl = page.url()
  await main.getByRole('link', { name: 'Ver transação' }).click()
  await expect(page).toHaveURL(/\/transactions\/[0-9a-f-]{36}$/)
  await page.getByRole('link', { name: 'Ver insight relacionado' }).click()
  await expect(page).toHaveURL(unusualUrl)

  // M-More: contagem de Importantes.
  await page.goto('/more')
  await expect(main.getByRole('link', { name: /Insights/ })).toContainText(/\d+ importantes?/)

  // Responsivo (06-responsive): sem rolagem horizontal nas larguras de referência.
  for (const width of [375, 768, 1024, 1280, 1440]) {
    await page.setViewportSize({ width, height: 900 })
    for (const path of ['/insights', unusualUrl.replace(/^https?:\/\/[^/]+/, ''), '/home']) {
      await page.goto(path)
      await expect(page.getByRole('main')).toContainText(/Insight|Merece atenção/)
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
  }).toPass({ timeout: 150_000, intervals: [2_000, 3_000, 5_000] })
}

function escape(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

function capitalize(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1)
}
