import { getUsage } from '@/server/copilot'

export const dynamic = 'force-dynamic'

/** Uso do mês e do dia e o recorte de dados (ADR-0040 §8). */
export async function GET() {
  return getUsage()
}
