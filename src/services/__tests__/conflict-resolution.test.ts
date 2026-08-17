import { describe, expect, it } from 'vitest'
import { shouldAcceptRemote } from '../conflict-resolution'

describe('conflict resolution', () => {
  it('uses updatedAt first and a deterministic tie-breaker second', () => {
    expect(shouldAcceptRemote({ updatedAt: '2026-01-01T00:00:00Z', value: 'z' }, { updatedAt: '2026-01-02T00:00:00Z', value: 'a' })).toBe(true)
    expect(shouldAcceptRemote({ updatedAt: '2026-01-02T00:00:00Z', value: 'z' }, { updatedAt: '2026-01-01T00:00:00Z', value: 'a' })).toBe(false)
    expect(shouldAcceptRemote({ updatedAt: '2026-01-01T00:00:00Z', value: 'a' }, { updatedAt: '2026-01-01T00:00:00Z', value: 'z' })).toBe(true)
  })
})
