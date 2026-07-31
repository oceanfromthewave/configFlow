export interface WordToken {
    text: string
    changed: boolean
}

function tokenize(text: string): string[] {
    return text.match(/\S+|\s+/g) ?? []
}

/**
 * Word-level diff between one removed line and its paired added line.
 *
 * ponytail: common-prefix/suffix trim, not full LCS/Myers — a single changed
 * word or phrase highlights precisely, but scattered multi-word changes will
 * highlight as one wide blob. Upgrade to a real LCS if that shows up in practice.
 */
export function wordDiff(oldText: string, newText: string): {old: WordToken[]; new: WordToken[]} {
    const a = tokenize(oldText)
    const b = tokenize(newText)

    let prefix = 0
    while (prefix < a.length && prefix < b.length && a[prefix] === b[prefix]) prefix++

    let suffix = 0
    while (
        suffix < a.length - prefix &&
        suffix < b.length - prefix &&
        a[a.length - 1 - suffix] === b[b.length - 1 - suffix]
        ) suffix++

    const toTokens = (tokens: string[], start: number, end: number): WordToken[] => [
        ...tokens.slice(0, start).map((text) => ({text, changed: false})),
        ...tokens.slice(start, end).map((text) => ({text, changed: true})),
        ...tokens.slice(end).map((text) => ({text, changed: false})),
    ]

    return {
        old: toTokens(a, prefix, a.length - suffix),
        new: toTokens(b, prefix, b.length - suffix),
    }
}
