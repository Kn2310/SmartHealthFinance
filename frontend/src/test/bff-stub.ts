import { vi } from 'vitest'

export const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

type Route = (init: RequestInit | undefined, url: string) => Response | Promise<Response>

/**
 * `fetch` falso do browser → BFF, roteado por "MÉTODO prefixo" (o prefixo mais longo vence). Registra cada
 * chamada; rota não mapeada responde 502 UNAVAILABLE.
 */
export function stubBff(routes: Record<string, Route>) {
  const calls: { method: string; url: string; init?: RequestInit }[] = []
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    calls.push({ method, url, init })
    const match = Object.keys(routes)
      .sort((a, b) => b.length - a.length)
      .find((key) => {
        const [m, prefix] = key.split(' ')
        return m === method && url.startsWith(prefix!)
      })
    return match ? routes[match]!(init, url) : json({ code: 'UNAVAILABLE' }, 502)
  })
  vi.stubGlobal('fetch', fetchMock)
  return calls
}
