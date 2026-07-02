import { cn } from '@/shared/lib/cn'

export interface TabItem<T extends string = string> {
  id: T
  label: string
}

export interface TabsProps<T extends string> {
  items: ReadonlyArray<TabItem<T>>
  active: T
  onChange: (id: T) => void
  size?: 'sm' | 'md'
  className?: string
  'aria-label'?: string
}

export function Tabs<T extends string>({
  items,
  active,
  onChange,
  size = 'md',
  className,
  'aria-label': ariaLabel,
}: TabsProps<T>) {
  return (
    <div
      role="tablist"
      aria-label={ariaLabel}
      className={cn('flex items-center gap-0.5', className)}
    >
      {items.map((item) => {
        const isActive = item.id === active
        return (
          <button
            key={item.id}
            type="button"
            role="tab"
            aria-selected={isActive}
            onClick={() => onChange(item.id)}
            className={cn(
              'select-none rounded-md font-medium transition-colors',
              'outline-none focus-visible:ring-2 focus-visible:ring-accent/60',
              size === 'sm' ? 'h-6 px-2 text-xs' : 'h-7 px-3 text-sm',
              isActive
                ? 'bg-elevated text-primary'
                : 'text-muted hover:bg-elevated/60 hover:text-primary',
            )}
          >
            {item.label}
          </button>
        )
      })}
    </div>
  )
}
