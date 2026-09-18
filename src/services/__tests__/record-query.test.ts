import { describe, expect, it } from 'vitest'
import type { FuelRecord } from '../../types'
import { listRecordPage, summarizeVehicle } from '../record-query'

const stamp = '2026-09-18T00:00:00.000Z'
function record(id: string, overrides: Partial<FuelRecord> = {}): FuelRecord {
  return {
    id,
    vehicleId: 'car',
    date: '2026-09-18',
    odometer: 1000,
    liters: 40,
    amount: 280,
    pumpAmount: 300,
    pricePerLiter: 7,
    isFull: true,
    station: '',
    note: '',
    createdAt: stamp,
    updatedAt: stamp,
    deletedAt: null,
    ...overrides,
  }
}

describe('record queries', () => {
  it('uses a deterministic cursor without duplicates', () => {
    const records = [record('c', { odometer: 3000 }), record('b', { odometer: 2000 }), record('a', { odometer: 1000 })]
    const first = listRecordPage(records, { vehicleId: 'car', limit: 2 })
    const second = listRecordPage(records, { vehicleId: 'car', limit: 2, cursor: first.nextCursor! })
    expect(first.items.map((item) => item.id)).toEqual(['c', 'b'])
    expect(second.items.map((item) => item.id)).toEqual(['a'])
    expect(second.nextCursor).toBeNull()
  })

  it('filters deleted records, month and text', () => {
    const records = [record('one', { station: '城南能源站' }), record('two', { date: '2026-08-01', note: '长途', deletedAt: stamp })]
    expect(listRecordPage(records, { search: '能源', month: '2026-09' }).items.map((item) => item.id)).toEqual(['one'])
    expect(listRecordPage(records, { includeDeleted: true }).items).toHaveLength(2)
  })

  it('summarizes active records only', () => {
    const summary = summarizeVehicle(
      [record('one'), record('two', { amount: 100, odometer: 1500 }), record('gone', { deletedAt: stamp })],
      'car',
    )
    expect(summary).toEqual({ vehicleId: 'car', recordCount: 2, totalPaid: 380, currentOdometer: 1500 })
  })
})
