// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { ConfigError, readEnv } from './env'

const base = {
  OIDC_ISSUER_URI: 'https://idp.example/realms/shf',
  OIDC_CLIENT_ID: 'shf-web',
  API_BASE_URL: 'http://api:8080',
  SESSION_SECRET: 'x'.repeat(40),
}
const env = (extra: Record<string, string>) => ({ ...base, ...extra }) as unknown as NodeJS.ProcessEnv

describe('readEnv — produção nunca sobe em HTTP silenciosamente', () => {
  it('NODE_ENV=production + APP_BASE_URL http → erro de configuração', () => {
    expect(() => readEnv(env({ NODE_ENV: 'production', APP_BASE_URL: 'http://app.example' }))).toThrow(ConfigError)
    expect(() => readEnv(env({ NODE_ENV: 'production', APP_BASE_URL: 'http://localhost:3000' }))).toThrow(/https/)
  })

  it('produção com https → cookies Secure', () => {
    expect(readEnv(env({ NODE_ENV: 'production', APP_BASE_URL: 'https://app.example' })).secureCookies).toBe(true)
  })

  it('exceção explícita só para loopback com SHF_ALLOW_INSECURE_LOCALHOST=true', () => {
    const local = readEnv(env({ NODE_ENV: 'production', APP_BASE_URL: 'http://localhost:3000', SHF_ALLOW_INSECURE_LOCALHOST: 'true' }))
    expect(local.secureCookies).toBe(false)
    expect(() =>
      readEnv(env({ NODE_ENV: 'production', APP_BASE_URL: 'http://app.example', SHF_ALLOW_INSECURE_LOCALHOST: 'true' })),
    ).toThrow(ConfigError)
  })

  it('desenvolvimento aceita http://localhost', () => {
    expect(readEnv(env({ NODE_ENV: 'development', APP_BASE_URL: 'http://localhost:3000' })).secureCookies).toBe(false)
  })

  it('variável ausente, URL inválida ou segredo curto → erro de configuração', () => {
    expect(() => readEnv(env({ NODE_ENV: 'development' }))).toThrow(/APP_BASE_URL/)
    expect(() => readEnv(env({ NODE_ENV: 'development', APP_BASE_URL: 'não é url' }))).toThrow(ConfigError)
    expect(() => readEnv(env({ NODE_ENV: 'development', APP_BASE_URL: 'http://localhost:3000', SESSION_SECRET: 'curto' }))).toThrow(
      /32/,
    )
  })
})
