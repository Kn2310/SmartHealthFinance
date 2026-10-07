import Link from 'next/link'
import type { ButtonHTMLAttributes, ReactNode } from 'react'
import styles from './Button.module.css'

type Variant = 'primary' | 'secondary'

type LinkButtonProps = { href: string; variant?: Variant; children: ReactNode; className?: string }
type NativeButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & { href?: undefined; variant?: Variant }

function classes(variant: Variant, className?: string) {
  return [styles.button, styles[variant], className].filter(Boolean).join(' ')
}

/** Link (quando há `href`) ou botão, com o mesmo visual do Design System. */
export function Button(props: LinkButtonProps | NativeButtonProps) {
  if (props.href !== undefined) {
    const { href, variant = 'primary', children, className } = props
    return (
      <Link href={href} className={classes(variant, className)}>
        {children}
      </Link>
    )
  }
  const { variant = 'primary', className, type = 'button', ...rest } = props
  return <button type={type} className={classes(variant, className)} {...rest} />
}
