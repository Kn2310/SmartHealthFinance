import { expect, test } from './support/test'

/**
 * Consentimentos ponta a ponta (M11 Parte 0; ADR-0038): opt-in, pré-requisito do histórico, cascata da revogação com
 * confirmação e persistência depois de recarregar. O caminho de entrada é o do M-More / submenu do Perfil.
 */
test('consentimentos do Copilot: conceder, pré-requisito, revogar em cascata e persistir', async ({ signedIn: page }) => {
  await page.goto('/profile')
  await expect(page).toHaveURL(/\/profile\/consents$/)
  await expect(page.getByRole('heading', { level: 1, name: 'Perfil' })).toBeVisible()

  const data = page.getByRole('switch', { name: 'Permitir que o Copilot use meus dados' })
  const history = page.getByRole('switch', { name: 'Guardar histórico de conversas do Copilot' })

  // Opt-in: tudo começa desligado e o histórico depende do uso de dados.
  await expect(data).toHaveAttribute('aria-checked', 'false')
  await expect(history).toBeDisabled()

  await data.click()
  await expect(data).toHaveAttribute('aria-checked', 'true')
  await expect(page.getByText(/^Concedido em \d{2} [a-z]{3} \d{4} · o Copilot só lê dados dentro do app$/)).toBeVisible()
  await expect(history).toBeEnabled()

  await history.click()
  await expect(history).toHaveAttribute('aria-checked', 'true')

  // Revogar o uso de dados com histórico ativo pede confirmação e revoga os dois.
  await data.click()
  await expect(page.getByText('Revogar e apagar o histórico?')).toBeVisible()
  await page.getByRole('button', { name: 'Revogar' }).click()
  await expect(data).toHaveAttribute('aria-checked', 'false')
  await expect(history).toHaveAttribute('aria-checked', 'false')

  await page.reload()
  await expect(data).toHaveAttribute('aria-checked', 'false')
  await expect(history).toBeDisabled()
  await expect(page.getByText(/^Revogado em \d{2} [a-z]{3} \d{4}/)).toBeVisible()

  // Sem rolagem horizontal nas larguras do DS.
  for (const width of [375, 768, 1024, 1280, 1440]) {
    await page.setViewportSize({ width, height: 900 })
    expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth), `${width}px`).toBeLessThanOrEqual(0)
  }
})

test('M-More leva aos Consentimentos', async ({ signedIn: page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/more')
  await page.getByRole('link', { name: 'Consentimentos' }).click()
  await expect(page).toHaveURL(/\/profile\/consents$/)
  await expect(page.getByRole('switch', { name: 'Permitir que o Copilot use meus dados' })).toBeVisible()
})
