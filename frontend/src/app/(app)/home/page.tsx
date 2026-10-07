import type { Metadata } from 'next'
import { Suspense } from 'react'
import { HomeSkeleton } from '@/features/home/HomeSkeleton'
import { HomeView } from '@/features/home/HomeView'
import { readSession } from '@/server/auth/session'

export const metadata: Metadata = { title: 'Início' }

export default async function HomePage() {
  const session = await readSession()
  const firstName = session?.displayName.trim().split(/\s+/)[0] ?? ''
  return (
    // useSearchParams (período na URL) exige Suspense; o fallback é o próprio skeleton da Home.
    <Suspense fallback={<HomeSkeleton />}>
      <HomeView firstName={firstName} />
    </Suspense>
  )
}
