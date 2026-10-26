import type { Page } from '@playwright/test'
import { expect, test } from './support/test'
import { brl, cents } from './support/money'

const ACCOUNT = 'Conta Integração E2E'
const OPENING = '2.000,00'
const CARD = { nickname: 'Cartão Integração E2E', last4: '7310' }
const PURCHASE = { amount: '600,00', installments: '3', description: 'Sofá Fictício E2E' }

/** Dia de hoje no fuso de negócio (ADR-0006 §10), como o backend. */
function businessDay(): number {
  return Number(new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Sao_Paulo', day: '2-digit' }).format(new Date()))
}

/**
 * Integração dos cartões (Sessão 6.2 parte E): uma compra de 600 em 3× no cartão de limite 1.000, hoje.
 * - Home (ADR-0006 §18): saldo total só das contas; "Faturas a pagar" com a parcela da fatura atual (200) e
 *   "Próximos eventos" com o fechamento, marcado "Parcial" (nunca "Estimado").
 * - Health v2 (ADR-0027): Credit = S(600 ÷ 1.000 = 60%) = 55, sem fatura vencida ainda; explicação com as parcelas
 *   futuras (2 × 200).
 * - Insights v2 (ADR-0028): D12 utilização de 60% no chip Cartões, com "Ver cartão" "Em breve" até a Parte F.
 * O fechamento fica a ~10 dias de hoje, então os números valem em qualquer dia do mês.
 */
test('cartão na Home, no Health e nos Insights, sem dupla contagem', async ({ signedIn: page }) => {
  test.setTimeout(300_000)
  const closingDay = ((businessDay() + 9) % 28) + 1
  const dueDay = closingDay <= 22 ? closingDay + 5 : closingDay + 5 - 28

  await page.goto('/accounts/new')
  await page.getByLabel('Nome da conta').fill(ACCOUNT)
  await page.getByLabel('Saldo de hoje').fill(OPENING)
  await page.getByLabel('Positivo').check()
  await page.getByRole('button', { name: 'Criar conta' }).click()
  await expect(page).toHaveURL(/\/accounts$/)

  await page.goto('/cards/new')
  await page.getByLabel('Apelido').fill(CARD.nickname)
  await page.getByLabel('Bandeira').selectOption('VISA')
  await page.getByLabel('4 últimos dígitos').fill(CARD.last4)
  await page.getByLabel('Limite').fill('1.000,00')
  await page.getByLabel('Dia de fechamento').fill(String(closingDay))
  await page.getByLabel('Dia de vencimento').fill(String(dueDay))
  await page.getByLabel('Conta que paga a fatura (opcional)').selectOption({ label: ACCOUNT })
  await page.getByRole('button', { name: 'Cadastrar cartão' }).click()

  await expect(page).toHaveURL(/\/transactions\/new\?type=CARD_PURCHASE/)
  await page.getByLabel('Cartão', { exact: true }).selectOption({ label: `${CARD.nickname} •••• ${CARD.last4}` })
  await page.getByLabel('Valor total da compra').fill(PURCHASE.amount)
  await page.getByLabel('Parcelas').fill(PURCHASE.installments)
  await page.getByLabel('Descrição', { exact: true }).fill(PURCHASE.description)
  await page.getByRole('button', { name: 'Registrar transação' }).click()
  await expect(page).toHaveURL(/registered=posted/)

  // Home: a dívida fica fora do saldo total, num bloco próprio; os eventos são só fatos.
  await page.goto('/home')
  await expect(page.getByRole('region', { name: 'Saldo total' })).toContainText(brl(cents(OPENING)))
  const toPay = page.getByRole('region', { name: 'Faturas a pagar' })
  await expect(toPay).toContainText('Fora do saldo total · 1 cartão')
  await expect(toPay).toContainText(brl(cents('200,00')))
  const events = page.getByRole('region', { name: 'Próximos eventos' })
  const closing = events.getByRole('listitem').filter({ hasText: `Fechamento da fatura ${CARD.nickname}` })
  await expect(closing).toContainText(brl(cents('200,00')))
  await expect(closing).toContainText('Parcial')
  await expect(events).not.toContainText('Estimado')

  // Health v2: Credit pela utilização agregada (o score geral ainda não sai: não há mês fechado).
  await expectAfterRefresh(page, '/home/health', async () => {
    const credit = page.getByRole('main').getByRole('article')
      .filter({ has: page.getByRole('heading', { name: 'Crédito' }) })
    await expect(credit).toContainText('55', { timeout: 1_000 })
    await expect(credit).toContainText(
      `Utilização de 60% do limite do ${CARD.nickname}, incluindo ${brl(cents('400,00'))} de parcelas futuras.`,
      { timeout: 1_000 })
  })

  // Insights v2: utilização alta no chip Cartões.
  const title = `A utilização do ${CARD.nickname} está em 60% do limite, incluindo parcelas futuras.`
  await expectAfterRefresh(page, '/insights?filter=CARDS', async () => {
    await expect(page.getByRole('main').getByRole('link', { name: new RegExp(escape(title)) }))
      .toBeVisible({ timeout: 1_000 })
  })
  const chips = page.getByRole('group', { name: 'Filtrar insights' })
  await expect(chips.getByRole('button', { name: 'Cartões 1' })).toHaveAttribute('aria-pressed', 'true')
  await page.getByRole('main').getByRole('link', { name: new RegExp(escape(title)) }).click()
  await expect(page.getByRole('heading', { level: 1, name: title })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Ver cartão · Em breve' })).toBeDisabled()

  // Sem rolagem horizontal nas larguras do DS com os blocos novos.
  for (const width of [375, 768, 1024, 1280, 1440]) {
    await page.setViewportSize({ width, height: 900 })
    for (const path of ['/home', '/insights?filter=CARDS']) {
      await page.goto(path)
      await expect(page.getByRole('main')).toBeVisible()
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
      expect(overflow, `${path} em ${width}px`).toBeLessThanOrEqual(0)
    }
  }
})

function escape(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

async function expectAfterRefresh(page: Page, path: string, assertion: () => Promise<void>) {
  await page.goto(path)
  await expect(async () => {
    await page.reload()
    await assertion()
  }).toPass({ timeout: 120_000, intervals: [1_000, 2_000, 3_000] })
}
