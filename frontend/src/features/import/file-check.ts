/** Mesmo limite do backend (ADR-0009 §3). */
export const MAX_FILE_BYTES = 10 * 1024 * 1024

export const ACCEPT = '.ofx,.csv'

/**
 * Checagem no navegador antes do envio, só para poupar a viagem: o backend valida de novo (extensão, conteúdo,
 * tamanho). Devolve o mesmo código de recusa do backend, ou null.
 */
export function checkFile(file: File): string | null {
  const name = file.name.toLowerCase()
  if (!name.endsWith('.ofx') && !name.endsWith('.csv')) return 'UNSUPPORTED_FILE_TYPE'
  if (file.size === 0) return 'EMPTY_FILE'
  if (file.size > MAX_FILE_BYTES) return 'FILE_TOO_LARGE'
  return null
}

export function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`
  return `${(bytes / (1024 * 1024)).toLocaleString('pt-BR', { maximumFractionDigits: 1 })} MB`
}
