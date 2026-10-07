import { describe, expect, it } from 'vitest'
import { seal, unseal } from './seal'

const SECRET = 'x'.repeat(40)

describe('seal / unseal', () => {
  it('faz o round-trip e não expõe o conteúdo', () => {
    const sealed = seal({ accessToken: 'eyJ.secret.token', workspaceId: 'w1' }, SECRET, 'session')
    expect(sealed).not.toContain('secret')
    expect(unseal(sealed, SECRET, 'session')).toEqual({ accessToken: 'eyJ.secret.token', workspaceId: 'w1' })
  })

  it('rejeita adulteração, segredo errado e finalidade errada', () => {
    const sealed = seal({ a: 1 }, SECRET, 'session')
    const tampered = sealed.slice(0, -2) + (sealed.endsWith('AA') ? 'BB' : 'AA')
    expect(unseal(tampered, SECRET, 'session')).toBeNull()
    expect(unseal(sealed, 'y'.repeat(40), 'session')).toBeNull()
    expect(unseal(sealed, SECRET, 'flow')).toBeNull()
    expect(unseal('lixo', SECRET, 'session')).toBeNull()
  })

  it('cabe em um cookie com tokens do tamanho de um JWT do Keycloak', () => {
    const jwt = () => `${'a'.repeat(36)}.${Buffer.from(JSON.stringify({ sub: 'x', pad: 'z'.repeat(900) })).toString('base64url')}.${'s'.repeat(342)}`
    const sealed = seal({ accessToken: jwt(), refreshToken: jwt(), expiresAt: 1, workspaceId: 'w', displayName: 'Ana' }, SECRET, 'session')
    expect(sealed.length).toBeLessThan(3800)
  })
})
