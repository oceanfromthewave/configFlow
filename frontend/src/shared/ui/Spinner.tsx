import { cn } from '@/shared/lib/cn'

export interface SpinnerProps {
  size?: 'sm' | 'md'
  className?: string
  /** Accessible label; defaults to none (decorative). */
  label?: string
}

export function Spinner({ size = 'md', className, label }: SpinnerProps) {
  return (
    <span
      role={label ? 'status' : undefined}
      aria-label={label}
      className={cn(
        'inline-block animate-spin rounded-full border-2 border-muted/40 border-t-accent',
        size === 'sm' ? 'h-3.5 w-3.5' : 'h-5 w-5',
        className,
      )}
    />
  )
}
