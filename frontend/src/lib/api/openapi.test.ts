// @vitest-environment node
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import ts from 'typescript'
import { describe, expect, it } from 'vitest'

/**
 * Contrato manual (`schema.d.ts`) × snapshot do OpenAPI do backend (ADR-0010).
 *
 * O snapshot é gerado e verificado pelo `OpenApiContractIT` no `./mvnw verify`. Aqui conferimos o que o
 * `contract.test.ts` (records e enums Java) não cobre: rotas, métodos, parâmetros, corpos de requisição e os nomes de
 * campo dos schemas. Enums e nulos continuam fora — o springdoc não os declara (ADR-0007 §11).
 */
const SNAPSHOT = resolve(__dirname, '../../../../backend/src/test/resources/openapi/openapi.json')
const SCHEMA = resolve(__dirname, 'schema.d.ts')

/**
 * Schemas do contrato manual que o OpenAPI ainda não descreve (endpoints com corpo genérico). Quando o backend
 * passar a declará-los, o teste "lista de ausentes" falha e o schema entra na comparação.
 */
const NOT_IN_OPENAPI = ['ImportPageResponse', 'ImportRecordPageResponse', 'ImportRecordResponse']

type OpenApiParameter = { in: string; name: string; required?: boolean }
type OpenApiOperation = {
  parameters?: OpenApiParameter[]
  requestBody?: { content: Record<string, { schema: { $ref?: string } }> }
}
type OpenApi = {
  paths: Record<string, Record<string, OpenApiOperation>>
  components: { schemas: Record<string, { properties?: Record<string, unknown> }> }
}

const METHODS = new Set(['get', 'post', 'put', 'patch', 'delete'])

// --- leitura do schema.d.ts pela AST do TypeScript (sem regex sobre tipos) ---

const source = ts.createSourceFile(SCHEMA, readFileSync(SCHEMA, 'utf8'), ts.ScriptTarget.Latest, true)

const aliases = new Map<string, ts.TypeNode>()
const interfaces = new Map<string, ts.InterfaceDeclaration>()
source.forEachChild((node) => {
  if (ts.isTypeAliasDeclaration(node)) aliases.set(node.name.text, node.type)
  if (ts.isInterfaceDeclaration(node)) interfaces.set(node.name.text, node)
})

function nameOf(member: ts.TypeElement): string {
  const name = member.name!
  return ts.isIdentifier(name) || ts.isStringLiteral(name) ? name.text : name.getText(source)
}

/** Membros de um tipo literal, resolvendo aliases locais (`AccountPath`) e interseções. */
function membersOf(type: ts.TypeNode | undefined): ts.PropertySignature[] {
  if (!type) return []
  if (ts.isTypeLiteralNode(type)) return type.members.filter(ts.isPropertySignature)
  if (ts.isIntersectionTypeNode(type)) return type.types.flatMap(membersOf)
  if (ts.isTypeReferenceNode(type) && ts.isIdentifier(type.typeName)) return membersOf(aliases.get(type.typeName.text))
  return []
}

function member(type: ts.TypeNode | undefined, name: string): ts.PropertySignature | undefined {
  return membersOf(type).find((m) => nameOf(m) === name)
}

/** `{ id: string; email?: string }` → `['email?', 'id']`. */
function params(type: ts.TypeNode | undefined, required: boolean): string[] {
  return membersOf(type)
    .map((m) => nameOf(m) + (required && !m.questionToken ? '' : '?'))
    .sort()
}

/** `components['schemas']['X']` → `X`. */
function schemaRef(type: ts.TypeNode | undefined): string | undefined {
  if (type && ts.isIndexedAccessTypeNode(type) && ts.isLiteralTypeNode(type.indexType)) {
    return (type.indexType.literal as ts.StringLiteral).text
  }
  return undefined
}

const manualPaths = interfaces.get('paths')!.members.filter(ts.isPropertySignature)
const manualSchemas = membersOf(
  interfaces.get('components')!.members.filter(ts.isPropertySignature).find((m) => nameOf(m) === 'schemas')?.type,
)

function manualOperations() {
  return manualPaths.flatMap((path) =>
    membersOf(path.type)
      .filter((op) => METHODS.has(nameOf(op)))
      .map((op) => ({ path: nameOf(path), method: nameOf(op), type: op.type })),
  )
}

// --- comparação ---

const hasSnapshot = existsSync(SNAPSHOT)
const spec: OpenApi = hasSnapshot ? JSON.parse(readFileSync(SNAPSHOT, 'utf8')) : { paths: {}, components: { schemas: {} } }

function specParams(op: OpenApiOperation, location: string): string[] {
  return (op.parameters ?? [])
    .filter((p) => p.in === location)
    .map((p) => p.name + (p.required ? '' : '?'))
    .sort()
}

describe.skipIf(!hasSnapshot)('contrato manual × OpenAPI do backend', () => {
  it('o schema.d.ts declara rotas e schemas (o parser da AST encontrou o contrato)', () => {
    expect(manualOperations().length).toBeGreaterThan(10)
    expect(manualSchemas.length).toBeGreaterThan(10)
  })

  it.each(manualOperations().map((op) => [`${op.method.toUpperCase()} ${op.path}`, op] as const))(
    '%s existe no backend com os mesmos parâmetros',
    (_, { path, method, type }) => {
      const op = spec.paths[path]?.[method]
      expect(op, 'rota/método inexistente no OpenAPI do backend').toBeDefined()

      const parameters = member(type, 'parameters')?.type
      for (const location of ['path', 'query', 'header'] as const) {
        const declared = member(parameters, location)
        expect(params(declared?.type, !declared?.questionToken), location).toEqual(specParams(op!, location))
      }

      const body = membersOf(member(member(type, 'requestBody')?.type, 'content')?.type)
      const specBody = op!.requestBody?.content ?? {}
      expect(body.map(nameOf).sort(), 'media types do corpo').toEqual(Object.keys(specBody).sort())
      for (const media of body) {
        const ref = schemaRef(media.type)
        if (ref) expect(specBody[nameOf(media)]?.schema.$ref, 'schema do corpo').toBe(`#/components/schemas/${ref}`)
      }
    },
  )

  it.each(
    manualSchemas
      .map(nameOf)
      .filter((name) => !NOT_IN_OPENAPI.includes(name))
      .map((name) => [name]),
  )('schema %s tem os mesmos campos do backend', (name) => {
    const backend = spec.components.schemas[name]
    expect(backend, 'schema inexistente no OpenAPI do backend').toBeDefined()
    const fields = membersOf(manualSchemas.find((s) => nameOf(s) === name)!.type).map(nameOf)
    expect(fields.sort()).toEqual(Object.keys(backend!.properties ?? {}).sort())
  })

  it('lista de ausentes do OpenAPI continua verdadeira (remova o nome quando o backend passar a declará-lo)', () => {
    for (const name of NOT_IN_OPENAPI) {
      expect(manualSchemas.map(nameOf), name).toContain(name)
      expect(spec.components.schemas[name], name).toBeUndefined()
    }
  })
})
