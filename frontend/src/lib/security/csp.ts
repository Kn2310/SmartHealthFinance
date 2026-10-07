/**
 * Content Security Policy do app (ADR-0007 §CSP). Função pura para ser testável; aplicada por `src/proxy.ts`
 * com um nonce novo por requisição.
 *
 * - script-src: só scripts com o nonce da resposta + 'strict-dynamic' (os chunks que o runtime do Next carrega a
 *   partir deles). Sem 'unsafe-inline'. 'unsafe-eval' APENAS em desenvolvimento: o React usa eval para
 *   reconstruir stacks de erro no browser (documentação do Next.js); nunca em produção.
 * - style-src: 'self' (CSS Modules em arquivos) + nonce (estilos inline que o próprio Next gera). Sem
 *   'unsafe-inline' em produção: o app não usa atributo `style`. EXCEÇÃO SÓ EM DESENVOLVIMENTO: o `next dev`
 *   (Turbopack/HMR) injeta CSS inline sem nonce; verificado no browser, a política estrita bloqueia esses estilos.
 *   Em dev o style-src vira 'self' 'unsafe-inline' SEM nonce (com nonce presente o browser ignoraria o
 *   'unsafe-inline'). O build de produção foi verificado sem nenhuma violação.
 * - connect-src 'self': o browser só fala com o BFF; a API e o IdP nunca são chamados do browser.
 * - form-action 'self': o único form (logout) termina em rota do app (ADR-0008).
 * - frame-ancestors 'none' (equivale ao X-Frame-Options: DENY), object-src 'none', base-uri 'self'.
 * - upgrade-insecure-requests só quando o app é servido por HTTPS (em http://localhost quebraria os assets).
 */
export interface CspOptions {
  nonce: string
  dev: boolean
  https: boolean
}

export function buildCsp({ nonce, dev, https }: CspOptions): string {
  const directives: [string, ...string[]][] = [
    ['default-src', "'self'"],
    ['script-src', "'self'", `'nonce-${nonce}'`, "'strict-dynamic'", ...(dev ? ["'unsafe-eval'"] : [])],
    dev ? ['style-src', "'self'", "'unsafe-inline'"] : ['style-src', "'self'", `'nonce-${nonce}'`],
    ['img-src', "'self'", 'data:', 'blob:'],
    ['font-src', "'self'"],
    ['connect-src', "'self'"],
    ['object-src', "'none'"],
    ['base-uri', "'self'"],
    ['form-action', "'self'"],
    ['frame-ancestors', "'none'"],
  ]
  if (https) directives.push(['upgrade-insecure-requests'])
  return directives.map((d) => d.join(' ')).join('; ')
}

/** Nonce de 128 bits em base64 (Web Crypto: funciona no runtime do proxy). */
export function createNonce(): string {
  const bytes = new Uint8Array(16)
  crypto.getRandomValues(bytes)
  return btoa(String.fromCharCode(...bytes))
}
