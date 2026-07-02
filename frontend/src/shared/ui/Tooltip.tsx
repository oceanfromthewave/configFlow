import type { ReactNode } from 'react'

import { cn } from '@/shared/lib/cn'

export interface TooltipProps {
  label: string
  children: ReactNode
  side?: 'top' | 'bottom'
  className?: string
}

/**
 * CSS-only tooltip (hover/focus-within). Enough for M0; can be replaced by
 * a positioning-engine implementation when overflow cases appear.
 */
export function Tooltip({
  label,
  children,
  side = 'bottom',
  className,
}: TooltipProps) {
  return (
    <span className={cn('group/tooltip relative inline-flex', className)}>
      {children}
      <span
        role="tooltip"
        className={cn(
          'pointer-events-none absolute left-1/2 z-50 -translate-x-1/2 whitespace-nowrap',
          'rounded border border-border bg-elevated px-2 py-1 text-xs text-primary shadow-lg',
          'opacity-0 transition-opacity delay-150 duration-100',
          'group-hover/tooltip:opacity-100 group-focus-within/tooltip:opacity-100',
          side === 'bottom' ? 'top-full mt-1.5' : 'bottom-full mb-1.5',
        )}
      >
        {label}
      </span>
    </span>
  )
}
