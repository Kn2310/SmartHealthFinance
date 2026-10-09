import { expect, test } from './support/test'

/** Usuário recém-cadastrado: Workspace pessoal sem contas → estado NO_ACCOUNTS (ADR-0006 §11), sem nenhum valor. */
test('Home vazia de um usuário novo mostra só o próximo passo', async ({ signedIn: page }) => {
  await expect(page.getByRole('heading', { name: 'Você ainda não tem contas' })).toBeVisible()
  await expect(page.getByRole('main').getByRole('link', { name: 'Adicionar conta' })).toHaveAttribute('href', '/accounts/new')
  // Sem dados ≠ zero: nenhum saldo nem fluxo é desenhado.
  await expect(page.getByRole('region', { name: 'Saldo total' })).toHaveCount(0)
  await expect(page.getByText('R$', { exact: false })).toHaveCount(0)
})
