import { expect, test } from './support/test'
import { cardStatement } from './support/card-statement'
import { definition } from './support/locators'
import { brl, cents } from './support/money'

const ACCOUNT = 'Conta Corrente Fatura E2E'
const OPENING = '5.000,00'
const CARD = { nickname: 'Aurora Fictício E2E', last4: '4821' }
/** O débito "PAG FATURA" do extrato da conta: o valor da fatura atual (86,40 + 480,00 − 20,00). */
const INVOICE_PAYMENT = '546,40'

/**
 * Fatura de cartão importada (ADR-0009 §19–§30) e pagamento conciliado pela conta (ADR-0025 §7): a compra parcelada
 * entra uma vez, pelo total, na data da compra; o pagamento que aparece na fatura fica de fora; o débito da conta vira
 * transferência para o cartão. Na Home do mês, nada é contado duas vezes:
 *   despesas = compras do mês no cartão (o notebook é de 2 meses atrás)
 *   saldo    = saldo inicial − pagamento da fatura
 */
test('fatura importada e pagamento conciliado não contam nada duas vezes', async ({ signedIn: page }) => {
  const statement = cardStatement()
  const [notebook, bistro, refund, payment] = statement.lines

  await page.goto('/accounts/new')
  await page.getByLabel('Nome da conta').fill(ACCOUNT)
  await page.getByLabel('Saldo de hoje').fill(OPENING)
  await page.getByLabel('Positivo').check()
  await page.getByRole('button', { name: 'Criar conta' }).click()
  await expect(page).toHaveURL(/\/accounts$/)

  await page.goto('/cards/new')
  await page.getByLabel('Apelido').fill(CARD.nickname)
  await page.getByLabel('Bandeira').selectOption('MASTERCARD')
  await page.getByLabel('4 últimos dígitos').fill(CARD.last4)
  await page.getByLabel('Limite').fill('12.000,00')
  await page.getByLabel('Dia de fechamento').fill('28')
  await page.getByLabel('Dia de vencimento').fill('5')
  await page.getByLabel('Conta que paga a fatura (opcional)').selectOption({ label: ACCOUNT })
  await page.getByRole('button', { name: 'Cadastrar cartão' }).click()
  await expect(page).toHaveURL(/\/transactions\/new/)

  // Fatura: o cartão é o destino; o CSV usa o sinal de fatura (positivo = compra).
  await page.goto('/import')
  await page.getByRole('radio', { name: new RegExp(CARD.nickname) }).check()
  await expect(page.getByRole('link', { name: 'Baixar modelo de fatura' })).toBeVisible()
  await page.locator('input[type="file"]').setInputFiles({
    name: 'fatura-ficticia-aurora.csv',
    mimeType: 'text/csv',
    buffer: statement.buffer,
  })
  await page.getByRole('button', { name: 'Continuar' }).click()

  const main = page.getByRole('main')
  await expect(definition(main, 'Novas para importar')).toHaveText('3')
  await expect(definition(main, 'Pagamentos (pela conta)')).toHaveText('1')
  await expect(main).toContainText(`Parcela 3 de 10`)
  await expect(main).toContainText('Pagamento: registre pela conta')
  await page.getByRole('button', { name: 'Importar 3 movimentações' }).click()
  await expect(page.getByRole('heading', { name: '3 movimentações importadas' })).toBeVisible({ timeout: 60_000 })

  // A compra parcelada foi reconstruída pelo total (parcela × 10), na data da compra.
  await page.goto(`/transactions?period=CUSTOM&from=${statement.purchaseDate}&to=${statement.purchaseDate}`)
  const purchase = page.getByRole('link', { name: new RegExp('Loja Tech Fictícia') })
  await expect(purchase).toContainText(brl(cents('480,00') * 10n))
  expect(notebook!.installment).toBe('3/10')

  // O débito do extrato da conta é o pagamento da fatura: vira transferência para o cartão.
  await page.goto('/import')
  await page.getByRole('radio', { name: new RegExp(ACCOUNT) }).check()
  await page.locator('input[type="file"]').setInputFiles({
    name: 'extrato-conta.csv',
    mimeType: 'text/csv',
    buffer: Buffer.from(
      `data;descricao;valor;id\r\n${statement.today.split('-').reverse().join('/')};PAG FATURA AURORA FICTÍCIO;-${INVOICE_PAYMENT};FIC-2026-0201\r\n`,
      'utf8',
    ),
  })
  await page.getByRole('button', { name: 'Continuar' }).click()
  await page.getByRole('button', { name: 'Importar 1 movimentação' }).click()
  await expect(page.getByRole('heading', { name: '1 movimentação importada' })).toBeVisible({ timeout: 60_000 })

  await page.goto('/transactions')
  await page.getByRole('link', { name: /PAG FATURA AURORA FICTÍCIO/ }).click()
  await page.getByRole('button', { name: 'Marcar como pagamento de fatura' }).click()
  // A conta que paga a fatura sugere o cartão.
  await expect(page.getByRole('radio', { name: new RegExp(CARD.nickname) })).toBeChecked()
  await page.getByRole('button', { name: 'Confirmar: marcar como pagamento de fatura' }).click()
  await expect(page.getByRole('link', { name: 'Ver despesa original' })).toBeVisible()

  await page.goto('/home')
  await expect(page.getByRole('region', { name: 'Saldo total' })).toContainText(
    brl(cents(OPENING) - cents(INVOICE_PAYMENT)),
  )
  const flow = page.getByRole('region', { name: 'Fluxo do período' })
  // Só a compra do mês é despesa: nem o pagamento da fatura nem o pagamento citado na fatura contam.
  await expect(definition(flow, 'Despesas')).toContainText(brl(bistro!.cents))
  await expect(definition(flow, 'Reembolsos')).toContainText(brl(-refund!.cents))
  await expect(definition(flow, 'Receitas')).toContainText(brl(0n))
  expect(payment!.description).toBe('PAGAMENTO RECEBIDO')
})
