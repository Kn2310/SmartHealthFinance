import { randomBytes, randomUUID } from 'node:crypto'
import { expect, type Page } from '@playwright/test'

/**
 * Cadastra um usuário NOVO pelo auto-cadastro do Keycloak local (`registrationAllowed` no realm) e termina na Home.
 * Passa pelo fluxo real: /home → /auth/login (PKCE) → Keycloak → /auth/callback → provisionamento → Workspace pessoal.
 *
 * O e-mail é fictício (@example.com) e a senha é aleatória, gerada em memória para este teste: nunca é gravada,
 * logada nem reutilizada, e o usuário só existe no Keycloak local descartável.
 */
export async function signUpNewUser(page: Page): Promise<void> {
  const email = `e2e-${randomUUID()}@example.com`
  const secret = `${randomBytes(18).toString('base64url')}Aa1!`

  await page.goto('/home')
  await expect(page).toHaveURL(/\/realms\/smart-health-finance\//)
  await page.getByRole('link', { name: 'Register' }).click()

  await page.locator('#email').fill(email)
  await page.locator('#password').fill(secret)
  await page.locator('#password-confirm').fill(secret)
  await page.locator('#firstName').fill('E2E')
  await page.locator('#lastName').fill('Teste')
  await page.getByRole('button', { name: 'Register' }).click()

  await expect(page).toHaveURL(/localhost:3000\/home/)
}
