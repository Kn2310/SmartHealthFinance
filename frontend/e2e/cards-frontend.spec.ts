import type { Page } from '@playwright/test'
import { expect, test } from './support/test'
import { definition } from './support/locators'
import { brl, cents } from './support/money'

const ACCOUNT = 'Conta Telas Cartão E2E'
const OPENING = '3.000,00'
const CARD = { nickname: 'Cartão Telas E2E', last4: '5310' }
const CARD_LABEL = `${CARD.nickname} •••• ${CARD.last4}`
const PURCHASE = { amount: '600,00', installments: '3', description: 'Estante Fictícia E2E' }
const INSTALLMENT = '200,00'
const ZERO_UUID = '00000000-0000-7000-8000-000000000000'

/** Dia de hoje no fuso de negócio (ADR-0006 §10), como o backend. */
function businessDay(): number {
  return Number(new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Sao_Paulo', day: '2-digit' }).format(new Date()))
}

/**
 * Telas de cartões (Sessão 6.2 parte F, ADR-0029), com datas relativas a hoje: o fechamento fica a ~10 dias, então a
 * compra de hoje cai na fatura aberta e os números valem em qualquer dia do mês.
 *   600 em 3× no limite de 1.000 → fatura atual 200 (parcial), 2 faturas futuras de 200, utilização 60%, sem parcelas
 *   futuras 20%; a parcela é 100% da fatura aberta. Pagar 200 deixa a fatura com 0 em aberto.
 * O frontend só exibe: os números conferidos aqui vêm prontos do backend.
 */
test('cadastro → compra parcelada → cartões → fatura → parcelamento → próximas faturas → pagamento', async ({ signedIn: page }) => {
  test.setTimeout(300_000)
  const closingDay = ((businessDay() + 9) % 28) + 1
  const dueDay = closingDay <= 22 ? closingDay + 5 : closingDay + 5 - 28

  await page.goto('/accounts/new')
  await page.getByLabel('Nome da conta').fill(ACCOUNT)
  await page.getByLabel('Saldo de hoje').fill(OPENING)
  await page.getByLabel('Positivo').check()
  await page.getByRole('button', { name: 'Criar conta' }).click()
  await expect(page).toHaveURL(/\/accounts$/)

  // Primeira vez: "Cartões" na navegação abre /cards vazio, com o convite para cadastrar.
  await page.goto('/home')
  await page.getByRole('navigation').getByRole('link', { name: 'Cartões' }).first().click()
  await expect(page).toHaveURL(/\/cards$/)
  await expect(page.getByRole('heading', { name: 'Acompanhe seus cartões aqui' })).toBeVisible()
  await page.getByRole('main').getByRole('link', { name: 'Adicionar cartão' }).first().click()
  await expect(page).toHaveURL(/\/cards\/new\?from=cards/)
  await page.getByLabel('Apelido').fill(CARD.nickname)
  await page.getByLabel('Bandeira').selectOption('ELO')
  await page.getByLabel('4 últimos dígitos').fill(CARD.last4)
  await page.getByLabel('Limite').fill('1.000,00')
  await page.getByLabel('Dia de fechamento').fill(String(closingDay))
  await page.getByLabel('Dia de vencimento').fill(String(dueDay))
  await page.getByLabel('Conta que paga a fatura (opcional)').selectOption({ label: ACCOUNT })
  await page.getByRole('button', { name: 'Cadastrar cartão' }).click()
  await expect(page).toHaveURL(/\/cards\/[0-9a-f-]{36}$/)
  const cardId = page.url().split('/').pop()!
  await expect(page.getByRole('heading', { level: 1, name: CARD.nickname })).toBeVisible()

  await page.goto(`/transactions/new?type=CARD_PURCHASE&cardId=${cardId}`)
  await expect(page.getByLabel('Cartão', { exact: true }).locator('option:checked')).toHaveText(CARD_LABEL)
  await page.getByLabel('Valor total da compra').fill(PURCHASE.amount)
  await page.getByLabel('Parcelas').fill(PURCHASE.installments)
  await page.getByLabel('Descrição', { exact: true }).fill(PURCHASE.description)
  await page.getByRole('button', { name: 'Registrar transação' }).click()
  await expect(page).toHaveURL(/registered=posted/)

  // /cards: métricas e bloco do cartão com os valores do backend.
  await page.goto('/cards')
  const metrics = page.getByRole('region', { name: 'Resumo dos cartões ativos' })
  await expect(metrics).toContainText(brl(cents('1.000,00')))
  await expect(metrics).toContainText(brl(cents(PURCHASE.amount)))
  await expect(metrics).toContainText(brl(cents(INSTALLMENT)))
  const block = page.getByRole('article', { name: new RegExp(escape(CARD_LABEL)) })
  await expect(block).toContainText('Aberta')
  await expect(block).toContainText('60% do limite')
  await expect(block.getByRole('img', { name: 'Utilização de 60% do limite' })).toBeVisible()
  await expect(page.getByRole('region', { name: 'Parcelamentos' })).toContainText(PURCHASE.description)

  // Fatura atual: parcial, com a parcela 1/3 destacada e o comprometido da próxima, sem estimativa.
  await block.getByRole('link', { name: 'Ver fatura' }).click()
  await expect(page).toHaveURL(new RegExp(`/cards/${cardId}/invoice$`))
  const main = page.getByRole('main')
  await expect(main.getByText('Valor até agora')).toBeVisible()
  await expect(main).toContainText(brl(cents(INSTALLMENT)))
  await expect(main).toContainText('pode mudar até o fechamento')
  await expect(main).toContainText('Primeira fatura acompanhada pelo app.')
  await expect(main).toContainText('Pagamento mínimo: só quando informado no arquivo da fatura.')
  await expect(main).toContainText('regras invoice-v2')
  await expect(main).toContainText('Parcela 1 de 3')
  await expect(main.getByText('Já comprometido (parcelas)')).toBeVisible()
  await expect(main).not.toContainText('Estimativa')

  // Parcelamento: blocos, peso na fatura aberta e utilização sem parcelas futuras, do backend.
  await main.getByRole('link', { name: PURCHASE.description }).click()
  await expect(page).toHaveURL(new RegExp(`/cards/${cardId}/installments/[0-9a-f-]{36}$`))
  const blocks = page.getByRole('list', { name: 'Parcelas' })
  await expect(blocks.getByRole('listitem')).toHaveCount(3)
  await expect(blocks).toContainText('Nesta fatura')
  await expect(page.getByRole('main')).toContainText('100%')
  await expect(page.getByRole('main')).toContainText('20%')
  await expect(page.getByRole('link', { name: 'Ver saúde financeira' })).toHaveAttribute('href', '/home/health')

  // Próximas faturas: só o comprometido (2 × 200), com o aviso.
  await page.goto(`/cards/${cardId}/invoices`)
  await expect(page.getByRole('main')).toContainText('Novas compras ainda não aparecem aqui.')
  await expect(page.getByRole('main')).toContainText(`${brl(cents('400,00'))} em 2 faturas`)

  // Pagamento pela fatura: o formulário vem com o cartão e a conta padrão; a fatura passa a mostrar o pago.
  await page.goto(`/cards/${cardId}/invoice`)
  await page.getByRole('link', { name: 'Registrar pagamento' }).click()
  await expect(page.getByRole('radio', { name: 'Pagamento de fatura' })).toBeChecked()
  await expect(page.getByLabel('Pagar com a conta').locator('option:checked')).toHaveText(ACCOUNT)
  await page.getByLabel('Valor', { exact: true }).fill(INSTALLMENT)
  await page.getByRole('button', { name: 'Registrar transação' }).click()
  await expect(page).toHaveURL(/registered=posted/)
  await page.goto(`/cards/${cardId}/invoice`)
  await expect(definition(page.getByRole('main'), 'Pago')).toContainText(brl(cents(INSTALLMENT)))
  await expect(definition(page.getByRole('main'), 'Em aberto')).toContainText(brl(0n))
  await expect(page.getByRole('link', { name: 'Registrar pagamento' })).toHaveCount(0)

  // Filtro "Conta ou cartão" em /transactions: a compra aparece pelo cartão.
  await page.goto('/transactions')
  await page.getByLabel('Conta ou cartão').selectOption({ label: CARD_LABEL })
  await expect(page).toHaveURL(new RegExp(`cardId=${cardId}`))
  await expect(page.getByRole('main')).toContainText(PURCHASE.description)

  // Estados: fatura passada sem lançamentos e cartão inexistente.
  await page.goto(`/cards/${cardId}/invoices/2020-01`)
  await expect(page.getByRole('heading', { name: 'Fatura não encontrada' })).toBeVisible()
  await page.goto(`/cards/${ZERO_UUID}`)
  await expect(page.getByRole('heading', { name: 'Cartão não encontrado' })).toBeVisible()

  // Sem rolagem horizontal nas larguras do DS.
  const paths = ['/cards', `/cards/${cardId}`, `/cards/${cardId}/invoice`, `/cards/${cardId}/invoices`, `/cards/${cardId}/edit`]
  for (const width of [375, 768, 1024, 1280, 1440]) {
    await page.setViewportSize({ width, height: 900 })
    for (const path of paths) await expectNoHorizontalScroll(page, path, width)
  }
})

async function expectNoHorizontalScroll(page: Page, path: string, width: number) {
  await page.goto(path)
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
  expect(overflow, `${path} em ${width}px`).toBeLessThanOrEqual(0)
}

function escape(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}
