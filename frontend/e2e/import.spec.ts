import { expect, test } from './support/test'
import { definition } from './support/locators'
import { brl, sum } from './support/money'
import { readStatement, STATEMENT_PATH } from './support/statement'

/**
 * Importação (ADR-0009 §18): CSV fictício → preview → confirmação → processamento assíncrono → resultado → selo
 * "Importado". Setembro é aberto por período personalizado na URL, para o teste não depender da data de hoje.
 */
test('extrato CSV fictício é importado e aparece na Home com o selo "Importado"', async ({ signedIn: page }) => {
  const lines = readStatement()
  const september = lines.filter((line) => line.date.startsWith('2026-09'))

  await page.goto('/import')
  await page.getByRole('main').getByRole('link', { name: 'Adicionar conta' }).click()
  await page.getByLabel('Nome da conta').fill('Conta Importação E2E')
  await page.getByRole('button', { name: 'Criar e continuar' }).click()
  await expect(page).toHaveURL(/\/import\?accountId=/)

  await page.locator('input[type="file"]').setInputFiles(STATEMENT_PATH)
  await page.getByRole('button', { name: 'Continuar' }).click()

  // Preview: todas as linhas do fixture são válidas e novas.
  await expect(definition(page.getByRole('main'), 'Novas para importar')).toHaveText(String(lines.length))
  await expect(definition(page.getByRole('main'), 'Com problema')).toHaveText('0')
  await page.getByRole('button', { name: `Importar ${lines.length} movimentações` }).click()

  // O worker processa de forma assíncrona (outbox → RabbitMQ); a tela acompanha por polling.
  await expect(page.getByRole('heading', { name: `${lines.length} movimentações importadas` })).toBeVisible({
    timeout: 60_000,
  })
  await page.getByRole('link', { name: 'Ver meu panorama' }).click()
  await expect(page).toHaveURL(/\/home/)

  await page.goto('/home?period=CUSTOM&from=2026-09-01&to=2026-09-30')
  // Saldo em 30/09 = todas as linhas do extrato; fluxo = só setembro (sinal → tipo, ADR-0009 §9).
  await expect(page.getByRole('region', { name: 'Saldo total' })).toContainText(brl(sum(lines.map((l) => l.cents))))
  const flow = page.getByRole('region', { name: 'Fluxo do período' })
  await expect(definition(flow, 'Receitas')).toContainText(brl(sum(september.filter((l) => l.cents > 0n).map((l) => l.cents))))
  await expect(definition(flow, 'Despesas')).toContainText(brl(-sum(september.filter((l) => l.cents < 0n).map((l) => l.cents))))

  const recent = page.getByRole('region', { name: 'Movimentações recentes' })
  const latest = september.at(-1)!
  await expect(recent.getByRole('listitem').first()).toContainText(latest.description)
  await expect(recent.getByRole('listitem').first()).toContainText('Importado')
  await expect(recent.getByText('Importado', { exact: true })).toHaveCount(await recent.getByRole('listitem').count())
})

