import { expect, test } from './support/test'
import { definition } from './support/locators'
import { brl, cents } from './support/money'

const ACCOUNT = 'Conta Corrente E2E'
const OPENING = '1.000,00'
const CARD = { nickname: 'Cartão Fictício E2E', last4: '4821' }
const CARD_LABEL = `${CARD.nickname} •••• ${CARD.last4}`
const PURCHASE = { amount: '300,00', installments: '3', description: 'Geladeira Fictícia E2E' }
const PAYMENT = '100,00'

/**
 * Cartões (ADR-0025/0026): cadastrar cartão → compra parcelada → pagar fatura (transferência) → Home sem dupla contagem.
 * A compra é gasto uma vez, na data dela, pelo total (as parcelas só dividem entre as faturas); o pagamento da fatura é
 * transferência da conta para o cartão: tira do saldo, mas não é gasto de novo. O saldo total é só das contas.
 *   saldo    = saldo inicial − pagamento
 *   despesas = total da compra
 */
test('compra parcelada e pagamento de fatura fecham na Home sem dupla contagem', async ({ signedIn: page }) => {
  await page.goto('/accounts/new')
  await page.getByLabel('Nome da conta').fill(ACCOUNT)
  await page.getByLabel('Saldo de hoje').fill(OPENING)
  await page.getByLabel('Positivo').check()
  await page.getByRole('button', { name: 'Criar conta' }).click()
  await expect(page).toHaveURL(/\/accounts$/)

  // Cadastro mínimo: só os 4 últimos dígitos; o formulário não tem campo de número completo, CVV, validade ou senha.
  await page.goto('/cards/new')
  await expect(page.getByLabel(/CVV|número do cartão|validade|senha/i)).toHaveCount(0)
  await page.getByLabel('Apelido').fill(CARD.nickname)
  await page.getByLabel('Bandeira').selectOption('MASTERCARD')
  await page.getByLabel('4 últimos dígitos').fill(CARD.last4)
  await page.getByLabel('Limite').fill('5.000,00')
  await page.getByLabel('Dia de fechamento').fill('28')
  await page.getByLabel('Dia de vencimento').fill('5')
  await page.getByLabel('Conta que paga a fatura (opcional)').selectOption({ label: ACCOUNT })
  await page.getByRole('button', { name: 'Cadastrar cartão' }).click()

  // Depois do cadastro, o formulário já abre em "Compra no cartão".
  await expect(page).toHaveURL(/\/transactions\/new\?type=CARD_PURCHASE/)
  await expect(page.getByRole('radio', { name: 'Compra no cartão' })).toBeChecked()
  await page.getByLabel('Cartão', { exact: true }).selectOption({ label: CARD_LABEL })
  await page.getByLabel('Valor total da compra').fill(PURCHASE.amount)
  await page.getByLabel('Parcelas').fill(PURCHASE.installments)
  await page.getByLabel('Descrição', { exact: true }).fill(PURCHASE.description)
  await page.getByRole('button', { name: 'Registrar transação' }).click()
  await expect(page).toHaveURL(/registered=posted/)

  await page.goto('/transactions/new')
  await page.getByRole('radio', { name: 'Pagamento de fatura' }).check()
  await page.getByLabel('Cartão', { exact: true }).selectOption({ label: CARD_LABEL })
  // A conta padrão do cartão vem sugerida.
  await expect(page.getByLabel('Pagar com a conta').locator('option:checked')).toHaveText(ACCOUNT)
  await page.getByLabel('Valor', { exact: true }).fill(PAYMENT)
  await page.getByRole('button', { name: 'Registrar transação' }).click()
  await expect(page).toHaveURL(/registered=posted/)

  await page.goto('/home')
  await expect(page.getByRole('region', { name: 'Saldo total' })).toContainText(brl(cents(OPENING) - cents(PAYMENT)))
  const flow = page.getByRole('region', { name: 'Fluxo do período' })
  await expect(definition(flow, 'Despesas')).toContainText(brl(cents(PURCHASE.amount)))
  await expect(definition(flow, 'Receitas')).toContainText(brl(0n))

  const recent = page.getByRole('region', { name: 'Movimentações recentes' })
  await expect(recent).toContainText(PURCHASE.description)
  await expect(recent).toContainText(CARD_LABEL)
  await expect(recent).toContainText('Pagamento de fatura')

  // Detalhe da compra: "Pago com" o cartão, sem conta.
  await page.goto('/transactions')
  await page.getByRole('link', { name: new RegExp(PURCHASE.description) }).click()
  await expect(page).toHaveURL(/\/transactions\/[0-9a-f-]{36}$/)
  await expect(page.getByRole('main')).toContainText('Pago com')
  await expect(page.getByRole('main')).toContainText(CARD_LABEL)
})
