import styles from './Skeleton.module.css'

type Width = 'xs' | 'sm' | 'md' | 'lg' | 'full'
type Height = 'caption' | 'body' | 'title' | 'value' | 'row'

/**
 * Bloco estático de carregamento (sem animação em loop — 01-foundations: "sem loops"). Tamanhos por classe,
 * não por `style` inline, para funcionar com a CSP sem `unsafe-inline`. O anúncio para leitores de tela fica
 * no contêiner (role="status"). `dark`: sobre o card Navy, translúcido (05-states).
 */
export function Skeleton({
  width = 'full',
  height = 'body',
  tone = 'light',
}: {
  width?: Width
  height?: Height
  tone?: 'light' | 'dark'
}) {
  const classes = [styles.skeleton, styles[`w-${width}`], styles[`h-${height}`], tone === 'dark' ? styles.dark : '']
  return <span className={classes.filter(Boolean).join(' ')} aria-hidden="true" />
}
