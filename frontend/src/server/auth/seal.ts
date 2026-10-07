import 'server-only'
import { createCipheriv, createDecipheriv, hkdfSync, randomBytes } from 'node:crypto'
import { deflateRawSync, inflateRawSync } from 'node:zlib'

/**
 * Sela um objeto JSON em uma string opaca (deflate + AES-256-GCM). O cookie de sessão carrega os tokens
 * criptografados: o browser não consegue ler nem alterar (GCM autentica), e nada vai para localStorage.
 * Formato: base64url(iv[12] | tag[16] | ciphertext).
 */

function key(secret: string, purpose: string): Buffer {
  return Buffer.from(hkdfSync('sha256', secret, 'shf-web', purpose, 32))
}

export function seal(value: unknown, secret: string, purpose: string): string {
  const iv = randomBytes(12)
  const cipher = createCipheriv('aes-256-gcm', key(secret, purpose), iv)
  const body = Buffer.concat([cipher.update(deflateRawSync(Buffer.from(JSON.stringify(value)))), cipher.final()])
  return Buffer.concat([iv, cipher.getAuthTag(), body]).toString('base64url')
}

export function unseal<T>(token: string, secret: string, purpose: string): T | null {
  try {
    const raw = Buffer.from(token, 'base64url')
    if (raw.length < 29) return null
    const decipher = createDecipheriv('aes-256-gcm', key(secret, purpose), raw.subarray(0, 12))
    decipher.setAuthTag(raw.subarray(12, 28))
    const plain = Buffer.concat([decipher.update(raw.subarray(28)), decipher.final()])
    return JSON.parse(inflateRawSync(plain).toString('utf8')) as T
  } catch {
    return null
  }
}
