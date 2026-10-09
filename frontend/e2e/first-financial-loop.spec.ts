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
 */
test('conta com saldo inicial, receita e despesa fecham na Home com aritmética exata', async ({ signedIn: page }) => {
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
})

async function registerTransaction(page: Page, type: 'Receita' | 'Despesa', { amount, description }: typeof INCOME) {
  await page.goto('/transactions/new')
  await page.getByRole('radio', { name: type }).check()
  await page.getByLabel('Conta').selectOption({ label: ACCOUNT })
  await page.getByLabel('Valor').fill(amount)
  await page.getByLabel('Descrição').fill(description)
  await page.getByRole('button', { name: 'Registrar transação' }).click()
  await expect(page).toHaveURL(/registered=posted/)
}

