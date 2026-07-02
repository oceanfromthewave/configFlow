import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useState,
  type ReactNode,
} from 'react'

import en from './en.json'
import ko from './ko.json'

/**
 * Minimal typed i18n.
 *
 * Why not i18next: M0 only needs static shell strings in ko/en with simple
 * `{var}` interpolation. This ~60-line provider gives compile-time key
 * checking (missing keys in en.json fail `tsc`) with zero bundle cost.
 * The `useT()` surface is i18next-compatible enough to swap later if
 * pluralization/ICU needs appear.
 */
export type Locale = 'ko' | 'en'
export type MessageKey = keyof typeof ko

const dictionaries: Record<Locale, Record<MessageKey, string>> = { ko, en }

export const DEFAULT_LOCALE: Locale = 'ko'

export type Translate = (
  key: MessageKey,
  vars?: Record<string, string | number>,
) => string

interface I18nContextValue {
  locale: Locale
  setLocale: (locale: Locale) => void
  t: Translate
}

const I18nContext = createContext<I18nContextValue | null>(null)

function format(template: string, vars?: Record<string, string | number>) {
  if (!vars) return template
  return template.replace(/\{(\w+)\}/g, (match, name: string) =>
    name in vars ? String(vars[name]) : match,
  )
}

export function I18nProvider({
  children,
  initialLocale = DEFAULT_LOCALE,
}: {
  children: ReactNode
  initialLocale?: Locale
}) {
  const [locale, setLocale] = useState<Locale>(initialLocale)

  const t = useCallback<Translate>(
    (key, vars) => format(dictionaries[locale][key] ?? key, vars),
    [locale],
  )

  const value = useMemo(
    () => ({ locale, setLocale, t }),
    [locale, t],
  )

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>
}

export function useI18n(): I18nContextValue {
  const context = useContext(I18nContext)
  if (!context) {
    throw new Error('useI18n must be used within <I18nProvider>')
  }
  return context
}

/** Shorthand: returns just the translate function. */
export function useT(): Translate {
  return useI18n().t
}
