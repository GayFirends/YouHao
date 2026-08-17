import { describe, expect, it } from 'vitest'
import { localDateKey, localMonthKey } from '../local-date'

describe('local date helpers', () => {
  it('uses the calendar date in the current timezone', () => {
    const earlyMorning = new Date(2026, 7, 1, 0, 30)
    expect(localDateKey(earlyMorning)).toBe('2026-08-01')
    expect(localMonthKey(earlyMorning)).toBe('2026-08')
  })
})
