// Materializa o Design System de design/ (fonte de verdade) dentro do app. Nada aqui é editado à mão:
//   design/tokens/tokens.css   → src/styles/design-tokens.css (cópia + bloco gerado do tokens.json)
//   design/tokens/tokens.json  → src/styles/design-tokens.json (para TS e testes de conformidade)
//   design/fonts/*             → public/fonts/
// Os arquivos gerados não são versionados; o script roda em predev/prebuild/prelint/pretest/pretypecheck.
import { cpSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const design = resolve(root, '..', 'design')

if (!existsSync(design)) {
  console.error('design/ não encontrado ao lado de frontend/. Os tokens são lidos de lá.')
  process.exit(1)
}

const tokens = JSON.parse(readFileSync(resolve(design, 'tokens/tokens.json'), 'utf8'))

/** "40/48 700" → { size: 40, line: 48, weight: 700 } */
function parseScale(step, value) {
  const match = /^(\d+)\/(\d+)\s+(\d{3})$/.exec(value)
  if (!match) throw new Error(`tokens.json font.scale.${step} em formato inesperado: "${value}"`)
  return { size: match[1], line: match[2], weight: match[3] }
}

/** Títulos usam a fonte de heading; texto e números, a de body (design/specs/01-foundations). */
const HEADING_STEPS = new Set(['display', 'h1', 'h2', 'h3'])

function typographyVars() {
  return Object.entries(tokens.font.scale).flatMap(([step, value]) => {
    const { size, line, weight } = parseScale(step, value)
    const family = HEADING_STEPS.has(step) ? 'var(--font-heading)' : 'var(--font-body)'
    return [
      `  --font-size-${step}: ${size}px;`,
      `  --line-height-${step}: ${line}px;`,
      `  --font-weight-${step}: ${weight};`,
      `  --type-${step}: ${weight} ${size}px/${line}px ${family};`,
    ]
  })
}

function gridVars() {
  const { mobile, tablet, desktop, large } = tokens.grid
  return [
    `  --grid-gutter-mobile: ${mobile.gutter}px;`,
    `  --grid-gutter-tablet: ${tablet.gutter}px;`,
    `  --grid-gutter-desktop: ${desktop.gutter}px;`,
    `  --grid-max: ${desktop.max}px;`,
    `  --grid-content-max: ${large['content-max']}px;`,
  ]
}

const generated = [
  '',
  '/* ---- Gerado de design/tokens/tokens.json por scripts/sync-design.mjs. Não edite. ---- */',
  `/* Breakpoints (media queries não aceitam variáveis): ${Object.entries(tokens.breakpoint)
    .map(([k, v]) => `${k} ${v}`)
    .join(' · ')} */`,
  ':root {',
  ...typographyVars(),
  ...gridVars(),
  '}',
  '',
].join('\n')

mkdirSync(resolve(root, 'src/styles'), { recursive: true })
writeFileSync(
  resolve(root, 'src/styles/design-tokens.css'),
  readFileSync(resolve(design, 'tokens/tokens.css'), 'utf8') + generated,
)
cpSync(resolve(design, 'tokens/tokens.json'), resolve(root, 'src/styles/design-tokens.json'))
cpSync(resolve(design, 'fonts'), resolve(root, 'public/fonts'), {
  recursive: true,
  filter: (src) => !src.endsWith('.txt'),
})
console.log('design tokens e fontes sincronizados')
