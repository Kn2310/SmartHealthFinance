import type { Metadata, Viewport } from 'next'
import { connection } from 'next/server'
import type { ReactNode } from 'react'
import tokens from '@/styles/design-tokens.json'
import '@/styles/globals.css'

export const metadata: Metadata = {
  title: { default: 'Smart Health Finance', template: '%s · Smart Health Finance' },
  description: 'Entenda seu dinheiro. Antecipe suas decisões. Cuide da sua saúde financeira.',
  robots: { index: false, follow: false },
}

export const viewport: Viewport = {
  width: 'device-width',
  initialScale: 1,
  themeColor: tokens.color.neutral.background,
}

export default async function RootLayout({ children }: { children: ReactNode }) {
  // CSP com nonce por requisição (src/proxy.ts) exige renderização dinâmica em todas as páginas.
  await connection()
  return (
    <html lang="pt-BR">
      <head>
        {/* Inter + Manrope self-hosted, sincronizadas de design/fonts (scripts/sync-design.mjs). */}
        <link rel="preload" href="/fonts/inter-latin-400-normal.woff2" as="font" type="font/woff2" crossOrigin="anonymous" />
        <link rel="preload" href="/fonts/manrope-latin-700-normal.woff2" as="font" type="font/woff2" crossOrigin="anonymous" />
        {/* eslint-disable-next-line @next/next/no-css-tags -- @font-face self-hosted em public/fonts */}
        <link rel="stylesheet" href="/fonts/fonts.css" />
      </head>
      <body>{children}</body>
    </html>
  )
}
