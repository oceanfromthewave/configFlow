import {describe, expect, it} from 'vitest'

import {wordDiff} from './wordDiff'

describe('wordDiff', () => {
    it('marks nothing changed for identical lines', () => {
        const {old, new: next} = wordDiff('hello world', 'hello world')
        expect(old.every((t) => !t.changed)).toBe(true)
        expect(next.every((t) => !t.changed)).toBe(true)
    })

    it('pinpoints a single changed word between a matching prefix and suffix', () => {
        const {old, new: next} = wordDiff('const foo = 1', 'const foo = 2')
        expect(old.filter((t) => t.changed).map((t) => t.text)).toEqual(['1'])
        expect(next.filter((t) => t.changed).map((t) => t.text)).toEqual(['2'])
        expect(old.filter((t) => !t.changed).map((t) => t.text)).toEqual(['const', ' ', 'foo', ' ', '=', ' '])
    })

    it('marks the whole line changed when nothing matches', () => {
        const {old, new: next} = wordDiff('abc', 'xyz')
        expect(old).toEqual([{text: 'abc', changed: true}])
        expect(next).toEqual([{text: 'xyz', changed: true}])
    })

    it('handles an empty old line (pure insert)', () => {
        const {old, new: next} = wordDiff('', 'new line')
        expect(old).toEqual([])
        expect(next.every((t) => t.changed)).toBe(true)
    })

    it('handles a whitespace-only line', () => {
        const {old, new: next} = wordDiff('   ', '  ')
        expect(old).toEqual([{text: '   ', changed: true}])
        expect(next).toEqual([{text: '  ', changed: true}])
    })

    it('keeps only the middle changed when prefix and suffix both match', () => {
        const {old, new: next} = wordDiff('a b c d', 'a X Y d')
        expect(old.map((t) => t.text)).toEqual(['a', ' ', 'b', ' ', 'c', ' ', 'd'])
        expect(old.filter((t) => t.changed).map((t) => t.text)).toEqual(['b', ' ', 'c'])
        expect(next.filter((t) => t.changed).map((t) => t.text)).toEqual(['X', ' ', 'Y'])
    })
})
