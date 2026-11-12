import type { Metadata } from 'next'
import { CopilotView } from '@/features/copilot/CopilotView'
import { parseEntry } from '@/features/copilot/entry'

export const metadata: Metadata = { title: 'Copilot' }

/**
 * D-Copilot. O contexto de entrada (`from` + ids) é validado aqui: parâmetro inválido é ignorado e a tela abre sem
 * contexto. Nada é enviado ao Copilot até a pessoa perguntar.
 */
export default async function CopilotPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>
}) {
  return <CopilotView entry={parseEntry(await searchParams)} />
}
