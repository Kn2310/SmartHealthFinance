import type { Metadata } from 'next'
import { HealthView } from '@/features/health/HealthView'

export const metadata: Metadata = { title: 'Saúde financeira' }

export default function HealthPage() {
  return <HealthView />
}
