// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { buildCsp, createNonce } from './csp'

const directives = (csp: string) =>
  Object.fromEntries(csp.split('; ').map((d) => [d.split(' ')[0], d.split(' ').slice(1)]))

describe('CSP', () => {
  it('produção: scripts só com nonce + strict-dynamic; sem unsafe-inline nem unsafe-eval', () => {
    const csp = buildCsp({ nonce: 'abc', dev: false, https: true })
    const d = directives(csp)
    expect(d['script-src']).toEqual(["'self'", "'nonce-abc'", "'strict-dynamic'"])
    expect(d['style-src']).toEqual(["'self'", "'nonce-abc'"])
    expect(csp).not.toMatch(/unsafe-inline|unsafe-eval/)
    expect(d['connect-src']).toEqual(["'self'"])
    expect(d['form-action']).toEqual(["'self'"])
    expect(d['frame-ancestors']).toEqual(["'none'"])
    expect(d['object-src']).toEqual(["'none'"])
    expect(d['base-uri']).toEqual(["'self'"])
    expect(d['font-src']).toEqual(["'self'"])
    expect(d).toHaveProperty('upgrade-insecure-requests')
  })

  it("desenvolvimento: 'unsafe-eval' nos scripts (stacks do React) e estilos inline do HMR; nada disso em produção", () => {
    const d = directives(buildCsp({ nonce: 'abc', dev: true, https: false }))
    expect(d['script-src']).toEqual(["'self'", "'nonce-abc'", "'strict-dynamic'", "'unsafe-eval'"])
    // sem nonce no style-src de dev: com nonce, o browser ignoraria o 'unsafe-inline'
    expect(d['style-src']).toEqual(["'self'", "'unsafe-inline'"])
    expect(d['script-src']).not.toContain("'unsafe-inline'")
  })

  it('sem HTTPS não força upgrade-insecure-requests (quebraria http://localhost)', () => {
    expect(buildCsp({ nonce: 'abc', dev: false, https: false })).not.toContain('upgrade-insecure-requests')
  })

  it('nonce é aleatório e com 128 bits', () => {
    const a = createNonce()
    expect(a).not.toBe(createNonce())
    expect(atob(a)).toHaveLength(16)
  })
})
