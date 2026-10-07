// Gera tipos a partir do OpenAPI da API em execução (springdoc) — multiplataforma (não depende do shell).
//
//   pnpm api:generate                                  # usa http://localhost:8080/v3/api-docs
//   OPENAPI_URL=https://.../v3/api-docs pnpm api:generate
//
// Saída: src/lib/api/openapi.generated.d.ts (NÃO versionado e NÃO importado pelo app).
// O contrato usado pelo app é src/lib/api/schema.d.ts (manual, temporário — ADR-0007 §6), porque o OpenAPI atual
// expõe enums como `string` e não marca campos nulos. Use o arquivo gerado para comparar e revisar o contrato.
import { writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import openapiTS, { astToString } from 'openapi-typescript'

const url = process.env.OPENAPI_URL || 'http://localhost:8080/v3/api-docs'
const out = resolve(dirname(fileURLToPath(import.meta.url)), '../src/lib/api/openapi.generated.d.ts')

try {
  const ast = await openapiTS(new URL(url))
  const header = `// GERADO de ${url} em ${new Date().toISOString()} — não editar, não importar (ver scripts/generate-api.mjs).\n`
  writeFileSync(out, header + astToString(ast))
  console.log(`tipos gerados em ${out}`)
} catch (error) {
  console.error(`Não foi possível gerar a partir de ${url}: ${error instanceof Error ? error.message : error}`)
  process.exit(1)
}
