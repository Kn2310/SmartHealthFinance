import styles from './Skeleton.module.css'

type Width = 'xs' | 'sm' | 'md' | 'lg' | 'full'
type Height = 'caption' | 'body' | 'title' | 'value' | 'row'

/**
 * Bloco estático de carregamento (sem animação em loop — 01-foundations: "sem loops"). Tamanhos por classe,
 * não por `style` inline, para funcionar com a CSP sem `unsafe-inline`. O anúncio para leitores de tela fica
 * no contêiner (role="status").
 */
export function Skeleton({ width = 'full', height = 'body' }: { width?: Width; height?: Height }) {
  return <span className={`${styles.skeleton} ${styles[`w-${width}`]} ${styles[`h-${height}`]}`} aria-hidden="true" />
}
