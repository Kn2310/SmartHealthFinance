import { defineConfig, devices } from '@playwright/test'

/**
 * E2E contra o ambiente local completo (compose com o perfil `app` + Keycloak) — ADR-0011.
 * O app precisa estar no ar: `docker compose --env-file backend/.env -f infrastructure/docker-compose.yml --profile app up -d --build`.
 *
 * Cada teste cria o próprio usuário pelo auto-cadastro do realm local (Workspace novo), então os testes não dependem
 * da ordem nem de limpeza. Trace e screenshot só ficam em caso de falha.
 */
export default defineConfig({
  testDir: './e2e',
  globalSetup: './e2e/global-setup.ts',
  outputDir: './test-results',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  // Teste instável é defeito (specs 05.10), não motivo para repetir.
  retries: 0,
  workers: process.env.CI ? 2 : undefined,
  timeout: 90_000,
  expect: { timeout: 15_000 },
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : [['list']],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:3000',
    locale: 'pt-BR',
    timezoneId: 'America/Sao_Paulo',
    // Trace próprio, iniciado só depois do login (e2e/support/test.ts) — a senha gerada nunca entra nele.
    trace: 'off',
    screenshot: 'only-on-failure',
    video: 'off',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})
