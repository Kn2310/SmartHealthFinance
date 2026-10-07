'use client'

import { usePathname, useRouter, useSearchParams } from 'next/navigation'
import { useCallback } from 'react'
import { formatPeriodRange } from '@/lib/format/date'
import { DEFAULT_QUERY, parseOverviewQuery, toSearchParams, type OverviewQuery } from '@/lib/overview-query'
import { HomeContent } from './HomeContent'
import { HomeError } from './HomeError'
import { HomeHeader } from './HomeHeader'
import { HomeSkeleton } from './HomeSkeleton'
import { PeriodSelector } from './PeriodSelector'
import { useOverview } from './useOverview'

/** Orquestra a Home: período na URL (compartilhável) → uma chamada ao BFF → estado semântico. */
export function HomeView({ firstName }: { firstName: string }) {
  const router = useRouter()
  const pathname = usePathname()
  const searchParams = useSearchParams()
  const query = parseOverviewQuery(searchParams)
  const result = useOverview(query)

  const changeQuery = useCallback(
    (next: OverviewQuery) => {
      const search = toSearchParams(next).toString()
      router.replace(search ? `${pathname}?${search}` : pathname, { scroll: false })
    },
    [router, pathname],
  )

  const selector = <PeriodSelector key={`${query.period}:${query.from}:${query.to}`} value={query} onChange={changeQuery} />

  if (result.status === 'loading') {
    return (
      <>
        <HomeHeader firstName={firstName} subtitle="Atualizando seus dados…">
          {selector}
        </HomeHeader>
        <HomeSkeleton header={false} />
      </>
    )
  }

  if (result.status === 'error') {
    return (
      <>
        <HomeHeader firstName={firstName} subtitle="Resumo financeiro">
          {selector}
        </HomeHeader>
        <HomeError kind={result.kind} onRetry={result.retry} onReset={() => changeQuery(DEFAULT_QUERY)} />
      </>
    )
  }

  const { data } = result
  const hasPeriodData = data.state === 'READY' || data.state === 'NO_ACTIVITY_IN_PERIOD'
  const subtitle = hasPeriodData
    ? `Resumo de ${formatPeriodRange(data.period.from, data.period.to)}`
    : data.state === 'NO_ACCOUNTS'
      ? 'Vamos começar'
      : 'Suas contas estão prontas'

  return (
    <>
      <HomeHeader firstName={firstName} subtitle={subtitle}>
        {hasPeriodData ? selector : null}
      </HomeHeader>
      <HomeContent overview={data} />
    </>
  )
}
