import axe from 'axe-core'

/**
 * Validação automatizada de acessibilidade (axe-core) sobre o DOM do jsdom.
 * Desligadas: `color-contrast` (jsdom não calcula estilos/cores reais — o contraste é dos tokens do DS) e
 * `region` (os testes renderizam fragmentos sem o shell com landmarks).
 */
export async function axeViolations(container: Element) {
  const result = await axe.run(container, {
    rules: { 'color-contrast': { enabled: false }, region: { enabled: false } },
  })
  return result.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`)
}
