import type { Locator } from '@playwright/test'

/** Valor (`dd`) de um termo (`dt`) nas listas de definição do app (`dl > div > (dt, dd)`): fluxo, contagens. */
export function definition(scope: Locator, term: string): Locator {
  // Ancorado no início: o `dt` de "Resultado líquido" tem a dica "Receitas + reembolsos − despesas".
  const dt = scope.page().locator('dt', { hasText: new RegExp(`^\\s*${term}`) })
  return scope.locator('dl > div').filter({ has: dt }).locator('dd')
}
