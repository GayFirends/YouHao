import { describe, expect, it } from 'vitest'
import { shouldAcceptRemote } from '../conflict-resolution'

describe('conflict resolution', () => {
  it('uses updatedAt first and a deterministic tie-breaker second', () => {
    expect(shouldAcceptRemote({ updatedAt: '2026-01-01T00:00:00Z', value: 'z' }, { updatedAt: '2026-01-02T00:00:00Z', value: 'a' })).toBe(true)
    expect(shouldAcceptRemote({ updatedAt: '2026-01-02T00:00:00Z', value: 'z' }, { updatedAt: '2026-01-01T00:00:00Z', value: 'a' })).toBe(false)
    expect(shouldAcceptRemote({ updatedAt: '2026-01-01T00:00:00Z', value: 'a' }, { updatedAt: '2026-01-01T00:00:00Z', value: 'z' })).toBe(true)
  })

  it('compares actual instants across timezone offsets and fractional formats', () => {
    expect(shouldAcceptRemote(
      { updatedAt: '2026-09-14T02:00:00.000Z' },
      { updatedAt: '2026-09-14T09:00:00+08:00' },
    )).toBe(false)
    expect(shouldAcceptRemote(
      { updatedAt: '2026-09-14T02:00:00Z' },
      { updatedAt: '2026-09-14T02:00:00.001Z' },
    )).toBe(true)
  })

  it('does not treat equivalent timestamp spellings as different records', () => {
    const local = { updatedAt: '2026-09-14T02:00:00.000Z', value: 'same' }
    const remote = { value: 'same', updatedAt: '2026-09-14T10:00:00+08:00' }
    expect(shouldAcceptRemote(local, remote)).toBe(false)
    expect(shouldAcceptRemote(remote, local)).toBe(false)
  })

  it('selects the same winner independently of field insertion order', () => {
    const local = { updatedAt: '2026-09-14T02:00:00Z', amount: 300, pumpAmount: 320, note: 'a' }
    const remote = { note: 'z', pumpAmount: 320, amount: 300, updatedAt: '2026-09-14T02:00:00Z' }
    expect(shouldAcceptRemote(local, remote)).toBe(true)
    expect(shouldAcceptRemote(remote, local)).toBe(false)
  })

  it('preserves deletion when an older backup has the same update timestamp', () => {
    const timestamp = '2026-09-14T02:00:00.000Z'
    const deleted: { updatedAt: string; deletedAt: string | null } = { updatedAt: timestamp, deletedAt: timestamp }
    const live: { updatedAt: string; deletedAt: string | null } = { updatedAt: timestamp, deletedAt: null }
    expect(shouldAcceptRemote(deleted, live)).toBe(false)
    expect(shouldAcceptRemote(live, deleted)).toBe(true)
    expect(shouldAcceptRemote(deleted, { ...live, updatedAt: '2026-09-14T02:01:00.000Z' })).toBe(true)
  })

  it('rejects invalid timestamps instead of silently choosing a winner', () => {
    expect(() => shouldAcceptRemote(undefined, { updatedAt: 'invalid' })).toThrow('更新时间无效')
  })
})
