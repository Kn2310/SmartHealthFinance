// @vitest-environment node
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import tokens from './design-tokens.json'

/**
 * Guarda de conformidade com o Design System (design/ é a fonte de verdade): impede que uma segunda linguagem
 * visual volte a crescer dentro do frontend.
 */
const SRC = resolve(__dirname, '..')

function cssFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) return cssFiles(path)
    return name.endsWith('.css') && name !== 'design-tokens.css' ? [path] : []
  })
}

const files = cssFiles(SRC).map((path) => ({
  name: relative(SRC, path),
  css: readFileSync(path, 'utf8').replace(/\/\*[\s\S]*?\*\//g, ''),
}))

/** "<640" | "640-1023" | ">=1440" → 640, 1024, 1440 */
const DS_BREAKPOINTS = new Set(
  Object.values(tokens.breakpoint).flatMap((range) => [...String(range).matchAll(/\d+/g)].map((m) => Number(m[0]))),
)
DS_BREAKPOINTS.add(1024)

describe('CSS do app × Design System', () => {
  it('encontra os arquivos CSS', () => {
    expect(files.length).toBeGreaterThan(5)
  })

  it.each(files.map((f) => [f.name, f.css]))('%s: sem cores literais (só tokens)', (_name, css) => {
    expect(css).not.toMatch(/#[0-9a-fA-F]{3,8}\b/)
    expect(css).not.toMatch(/\brgba?\(/)
    expect(css).not.toMatch(/\bhsla?\(/)
  })

  it.each(files.map((f) => [f.name, f.css]))('%s: media queries só nos breakpoints do DS (640/1024/1440)', (_name, css) => {
    for (const [, value] of css.matchAll(/@media[^{]*?(?:min|max)-width:\s*(\d+)px/g)) {
      expect(DS_BREAKPOINTS.has(Number(value)), `${value}px`).toBe(true)
    }
  })

  it.each(files.map((f) => [f.name, f.css]))('%s: tamanhos de fonte vêm dos tokens', (_name, css) => {
    const literal = [...css.matchAll(/font(?:-size)?:[^;]*?\b(\d+)px/g)].map((m) => m[1])
    // 13 px: label do Input definido em design/specs/02-components ("Label 13/600").
    expect(literal.filter((size) => size !== '13')).toEqual([])
  })

  it('não existe escala tipográfica paralela (--text-*) nem foco fora do DS', () => {
    const all = files.map((f) => f.css).join('\n')
    expect(all).not.toMatch(/--text-(display|h\d|body|caption)/)
    expect(all).not.toMatch(/--focus-ring/)
    expect(all).toMatch(/:focus-visible\s*\{\s*outline:\s*2px solid var\(--color-semantic-info\);\s*outline-offset:\s*2px;/)
  })

  it('sem animação em loop (01-foundations: "sem loops")', () => {
    for (const { name, css } of files) expect(css, name).not.toMatch(/infinite/)
  })
})
