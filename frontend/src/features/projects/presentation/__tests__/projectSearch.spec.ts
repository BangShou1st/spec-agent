import { describe, expect, it } from 'vitest'
import { normalizeQuery, splitTitleSegments } from '@/features/projects/presentation/projectSearch'

describe('normalizeQuery', () => {
  it('trims surrounding whitespace', () => {
    expect(normalizeQuery('  spec  ')).toBe('spec')
  })

  it('treats an all-whitespace query as no term', () => {
    expect(normalizeQuery('   ')).toBe('')
  })
})

describe('splitTitleSegments', () => {
  it('returns one unmatched segment when there is no query', () => {
    expect(splitTitleSegments('结算规格', '')).toEqual([{ text: '结算规格', matched: false }])
  })

  it('splits around the match', () => {
    expect(splitTitleSegments('结算规格梳理', '规格')).toEqual([
      { text: '结算', matched: false },
      { text: '规格', matched: true },
      { text: '梳理', matched: false },
    ])
  })

  it('handles a match at the start and at the end', () => {
    expect(splitTitleSegments('abcde', 'abc')).toEqual([
      { text: 'abc', matched: true },
      { text: 'de', matched: false },
    ])
    expect(splitTitleSegments('abcde', 'cde')).toEqual([
      { text: 'ab', matched: false },
      { text: 'cde', matched: true },
    ])
  })

  it('highlights every occurrence', () => {
    expect(splitTitleSegments('aXaXa', 'a')).toEqual([
      { text: 'a', matched: true },
      { text: 'X', matched: false },
      { text: 'a', matched: true },
      { text: 'X', matched: false },
      { text: 'a', matched: true },
    ])
  })

  it('preserves the original casing of the highlighted text', () => {
    expect(splitTitleSegments('Spec Agent', 'spec')).toEqual([
      { text: 'Spec', matched: true },
      { text: ' Agent', matched: false },
    ])
  })

  it('never loses characters — segments rejoin into the title', () => {
    const title = '结算规格梳理 v2'
    for (const query of ['结算', '格', 'v2', '梳理 v']) {
      const rejoined = splitTitleSegments(title, query)
        .map((segment) => segment.text)
        .join('')
      expect(rejoined).toBe(title)
    }
  })

  it('falls back to one unmatched segment when nothing matches', () => {
    expect(splitTitleSegments('abc', 'zzz')).toEqual([{ text: 'abc', matched: false }])
  })
})
