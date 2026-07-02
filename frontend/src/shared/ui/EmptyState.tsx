import type { ReactNode } from 'react'

import { cn } from '@/shared/lib/cn'

export interface EmptyStateProps {
  title: string
  description?: string
  icon?: ReactNode
  /** Next-action button(s) per docs/06 §4-5. */
  action?: ReactNode
  className?: string
}

export function EmptyState({
  title,
  description,
  icon,
  action,
  className,
}: EmptyStateProps) {
  return (
    <div
      className={cn(
        'flex h-full flex-col items-center justify-center gap-2 p-6 text-center',
        className,
      )}
    >
      {icon && <div className="mb-1 text-2xl text-muted">{icon}</div>}
      <p className="text-sm font-medium text-primary">{title}</p>
      {description && (
        <p className="max-w-64 text-xs leading-relaxed text-muted">
          {description}
        </p>
      )}
      {action && <div className="mt-2">{action}</div>}
    </div>
  )
}
