import { test as base, type Page } from '@playwright/test'
import { signUpNewUser } from './auth'

/**
 * `signedIn`: página já autenticada como um usuário NOVO (Workspace próprio, sem dados).
 *
 * O trace começa só DEPOIS do cadastro/login (ADR-0011): a senha gerada nunca entra no artefato. Ele é salvo apenas
 * quando o teste falha. Por isso o `trace` do runner fica desligado no playwright.config.ts.
 */
export const test = base.extend<{ signedIn: Page }>({
  // `provide` = o `use` do Playwright (renomeado: a regra de hooks do React confunde o nome).
  signedIn: async ({ page, context }, provide, testInfo) => {
    await signUpNewUser(page)
    await context.tracing.start({ screenshots: true, snapshots: true, sources: true, title: testInfo.title })

    await provide(page)

    if (testInfo.status !== testInfo.expectedStatus) {
      const path = testInfo.outputPath('trace.zip')
      await context.tracing.stop({ path })
      await testInfo.attach('trace', { path, contentType: 'application/zip' })
    } else {
      await context.tracing.stop()
    }
  },
})

export { expect } from '@playwright/test'
