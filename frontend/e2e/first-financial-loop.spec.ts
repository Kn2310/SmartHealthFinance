import type { Page } from '@playwright/test'
import { expect, test } from './support/test'
import { definition } from './support/locators'
import { brl, cents, sum } from './support/money'

const ACCOUNT = 'Conta Corrente E2E'
const OPENING = '1.000,00'
const INCOME = { amount: '2.500,00', description: 'Salário Fictício E2E' }
const EXPENSE = { amount: '432,10', description: 'Mercado Fictício E2E' }

/**
 * First Financial Loop (development-strategy): Workspace → Account → Transaction → Balance → Home.
 * O esperado é calculado em centavos aqui e comparado com o que o backend calculou (ADR-0006 §8/§9):
 *   saldo     = saldo inicial (ajuste) + receita − despesa
 *   resultado = receita − despesa  (o ajuste de saldo inicial não é receita)
 * Depois, a categorização (ADR-0013) e os mesmos totais na Análise (ADR-0015) e no topo de D-Transactions (ADR-0016).
 */
test('conta com saldo inicial, receita e despesa fecham na Home com aritmética exata', async ({ signedIn: page }) => {
  // Cenário longo (loop + categorização + Análise eventualmente consistente + 5 larguras): mais que os 90 s padrão.
  test.setTimeout(180_000)
  await page.goto('/accounts/new')
  await page.getByLabel('Nome da conta').fill(ACCOUNT)
  await page.getByLabel('Saldo de hoje').fill(OPENING)
  await page.getByLabel('Positivo').check()
  await page.getByRole('button', { name: 'Criar conta' }).click()
  await expect(page).toHaveURL(/\/accounts$/)
  await expect(page.getByRole('link', { name: ACCOUNT, exact: true })).toBeVisible()

  await registerTransaction(page, 'Receita', INCOME)
  await registerTransaction(page, 'Despesa', EXPENSE)

  const balance = cents(OPENING) + cents(INCOME.amount) - cents(EXPENSE.amount)
  const net = sum([cents(INCOME.amount), -cents(EXPENSE.amount)])

  await page.goto('/home')
  await expect(page.getByRole('region', { name: 'Saldo total' })).toContainText(brl(balance))
  const flow = page.getByRole('region', { name: 'Fluxo do período' })
  await expect(definition(flow, 'Receitas')).toContainText(brl(cents(INCOME.amount)))
  await expect(definition(flow, 'Despesas')).toContainText(brl(cents(EXPENSE.amount)))
  await expect(definition(flow, 'Resultado líquido')).toContainText(`mais ${brl(net)}`)

  const recent = page.getByRole('region', { name: 'Movimentações recentes' })
  await expect(recent).toContainText(INCOME.description)
  await expect(recent).toContainText(EXPENSE.description)
  await expect(recent).toContainText('Saldo inicial')

  // D-Transactions (ADR-0016): métricas do período filtrado, calculadas no backend com os filtros da lista.
  await page.goto('/transactions')
  const summary = page.getByRole('region', { name: 'Resumo do período filtrado' })
  await expect(definition(summary, 'Entradas').first()).toContainText(`mais ${brl(cents(INCOME.amount))}`)
  await expect(definition(summary, 'Saídas').first()).toContainText(`menos ${brl(cents(EXPENSE.amount))}`)
  await expect(definition(summary, 'Resultado').first()).toContainText(`mais ${brl(net)}`)

  // Categorização (ADR-0013): a regra do sistema pôs a despesa em Mercado; a pessoa corrige para Alimentação fora.
  await page.getByRole('link', { name: new RegExp(EXPENSE.description) }).click()
  await expect(page).toHaveURL(/\/transactions\/[0-9a-f-]{36}$/)
  await expect(page.getByText('Mercado · automática')).toBeVisible()
  await page.getByRole('combobox', { name: 'Categoria' }).selectOption({ label: 'Alimentação fora' })
  await page.getByRole('button', { name: 'Salvar categoria' }).click()
  await expect(page.getByText('Alimentação fora · escolhida por você')).toBeVisible()

  // Análise (ADR-0014/0015): a projeção é atualizada por eventos (consistência eventual); recarrega até refletir.
  await expectAfterRefresh(page, '/analysis/categories', async () => {
    const categories = page.getByRole('region', { name: 'Todas as categorias' })
    await expect(categories).toContainText('Alimentação fora', { timeout: 1_000 })
    await expect(categories).toContainText(brl(cents(EXPENSE.amount)), { timeout: 1_000 })
    await expect(categories).toContainText('100% das despesas', { timeout: 1_000 })
    await expect(categories).not.toContainText('Mercado', { timeout: 1_000 })
  })

  await page.goto('/analysis')
  const main = page.getByRole('main')
  await expect(main).toContainText(brl(cents(INCOME.amount)))
  await expect(main).toContainText(brl(cents(EXPENSE.amount)))
  await expect(main).toContainText(`mais ${brl(net)}`)
  const why = page.getByRole('list', { name: 'Composição do resultado do período' })
  await expect(why).toContainText('Alimentação fora')
  await expect(why).toContainText(`mais ${brl(net)}`)

  // Responsivo (design/specs/06-responsive): nenhuma rolagem horizontal da página nas larguras de referência.
  for (const width of [375, 768, 1024, 1280, 1440]) {
    await page.setViewportSize({ width, height: 900 })
    for (const path of ['/analysis', '/analysis/merchants', '/transactions']) {
      await page.goto(path)
      await expect(page.getByRole('main')).toContainText(brl(cents(EXPENSE.amount)))
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
      expect(overflow, `${path} em ${width}px`).toBeLessThanOrEqual(0)
    }
  }
})

/** Recarrega a página até a asserção passar: a Análise é eventualmente consistente (ADR-0014). */
async function expectAfterRefresh(page: Page, path: string, assertion: () => Promise<void>) {
  await page.goto(path)
  await expect(async () => {
    await page.reload()
    await assertion()
  }).toPass({ timeout: 30_000, intervals: [500, 1_000, 2_000] })
}

async function registerTransaction(page: Page, type: 'Receita' | 'Despesa', { amount, description }: typeof INCOME) {
  await page.goto('/transactions/new')
  await page.getByRole('radio', { name: type }).check()
  await page.getByLabel('Conta').selectOption({ label: ACCOUNT })
  await page.getByLabel('Valor').fill(amount)
  await page.getByLabel('Descrição').fill(description)
  await page.getByRole('button', { name: 'Registrar transação' }).click()
  await expect(page).toHaveURL(/registered=posted/)
}

