import { describe, expect, it } from 'vitest'
import type { FuelRecord } from '../../types'
import { listRecordPage, summarizeVehicle } from '../record-query'

describe('10k record performance budget', () => {
  it('builds the first ledger page and summary within 500 ms', () => {
    const stamp = '2026-09-18T00:00:00.000Z'
    const records: FuelRecord[] = Array.from({ length: 10_000 }, (_, index) => ({
      id: `record-${String(index).padStart(5, '0')}`,
      vehicleId: 'car',
      date: `2026-${String((index % 12) + 1).padStart(2, '0')}-${String((index % 28) + 1).padStart(2, '0')}`,
      odometer: index * 100,
      liters: 40,
      amount: 280,
      pumpAmount: 300,
      pricePerLiter: 7,
      isFull: true,
      station: `能源站 ${index % 20}`,
      note: '',
      createdAt: stamp,
      updatedAt: stamp,
      deletedAt: null,
    }))
    const start = performance.now()
    const page = listRecordPage(records, { vehicleId: 'car', limit: 50 })
    const summary = summarizeVehicle(records, 'car')
    const duration = performance.now() - start
    expect(page.items).toHaveLength(50)
    expect(summary.recordCount).toBe(10_000)
    expect(duration).toBeLessThan(500)
  })
})
