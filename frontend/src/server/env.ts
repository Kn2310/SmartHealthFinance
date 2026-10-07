import 'server-only'

export interface ServerEnv {
  appBaseUrl: string
  /** Cookies com `Secure`: verdadeiro sempre que o app é servido por HTTPS. */
  secureCookies: boolean
  issuer: string
  clientId: string
  apiBaseUrl: string
  sessionSecret: string
}

export class ConfigError extends Error {
  constructor(message: string) {
    super(`Configuração inválida: ${message} (veja frontend/.env.example)`)
    this.name = 'ConfigError'
  }
}

const LOOPBACK_HOSTS = new Set(['localhost', '127.0.0.1', '[::1]'])

function required(source: NodeJS.ProcessEnv, name: string): string {
  const value = source[name]?.trim()
  if (!value) throw new ConfigError(`variável de ambiente ausente: ${name}`)
  return value
}

function parseUrl(name: string, value: string): URL {
  try {
    return new URL(value)
  } catch {
    throw new ConfigError(`${name} não é uma URL válida`)
  }
}

const strip = (url: string) => url.replace(/\/+$/, '')

/**
 * Valida e lê a configuração do servidor. Em produção (`NODE_ENV=production`) o app SÓ inicia com
 * `APP_BASE_URL` em HTTPS: sem isso os cookies de sessão perderiam o `Secure`. Não há correção automática.
 * Exceção explícita e restrita: `SHF_ALLOW_INSECURE_LOCALHOST=true` com host de loopback, para rodar o build de
 * produção na própria máquina (`pnpm build && pnpm start`). Nunca vale para um domínio real.
 */
export function readEnv(source: NodeJS.ProcessEnv = process.env): ServerEnv {
  const appBaseUrl = strip(required(source, 'APP_BASE_URL'))
  const appUrl = parseUrl('APP_BASE_URL', appBaseUrl)
  const https = appUrl.protocol === 'https:'

  if (source.NODE_ENV === 'production' && !https) {
    const explicitLocal = source.SHF_ALLOW_INSECURE_LOCALHOST === 'true' && LOOPBACK_HOSTS.has(appUrl.hostname)
    if (!explicitLocal) {
      throw new ConfigError('em produção APP_BASE_URL precisa usar https:// (cookies de sessão exigem Secure)')
    }
  }

  const sessionSecret = required(source, 'SESSION_SECRET')
  if (sessionSecret.length < 32) throw new ConfigError('SESSION_SECRET precisa ter ao menos 32 caracteres')

  const issuer = strip(required(source, 'OIDC_ISSUER_URI'))
  const apiBaseUrl = strip(required(source, 'API_BASE_URL'))
  parseUrl('OIDC_ISSUER_URI', issuer)
  parseUrl('API_BASE_URL', apiBaseUrl)

  return {
    appBaseUrl,
    secureCookies: https,
    issuer,
    clientId: required(source, 'OIDC_CLIENT_ID'),
    apiBaseUrl,
    sessionSecret,
  }
}

/** Lido sob demanda (não no import) para o build não exigir segredos. Nunca é exposto ao browser. */
export function getEnv(): ServerEnv {
  return readEnv(process.env)
}
