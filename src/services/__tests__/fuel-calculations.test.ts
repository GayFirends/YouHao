import { describe, expect, it } from 'vitest'
import { calculateAverageConsumption, calculateConsumptionIntervals, fuelRecordWarnings } from '../fuel-calculations'
import type { FuelRecord } from '../../types'

function record(overrides: Partial<FuelRecord>): FuelRecord {
  return {
    id: crypto.randomUUID(), vehicleId: 'vehicle', date: '2026-01-01',
    odometer: 0, liters: 40, amount: 300, pricePerLiter: 7.5,
    isFull: true, station: '', note: '', createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(), deletedAt: null, ...overrides,
  }
}

describe('calculateConsumptionIntervals', () => {
  it('accumulates partial refuels between two full tanks', () => {
    const intervals = calculateConsumptionIntervals([
      record({ odometer: 1000, liters: 45, isFull: true }),
      record({ odometer: 1300, liters: 20, isFull: false }),
      record({ odometer: 1600, liters: 30, isFull: true }),
    ])
    expect(intervals).toHaveLength(1)
    expect(intervals[0]).toMatchObject({ distance: 600, liters: 50 })
    expect(intervals[0].consumption).toBeCloseTo(8.333, 3)
  })

  it('calculates a distance-weighted average', () => {
    const intervals = calculateConsumptionIntervals([
      record({ odometer: 1000, isFull: true }),
      record({ odometer: 1500, liters: 50, isFull: true }),
      record({ odometer: 2500, liters: 80, isFull: true }),
    ])
    expect(calculateAverageConsumption(intervals)).toBeCloseTo(8.6667, 3)
  })
})

describe('fuelRecordWarnings', () => {
  it('warns about reversed mileage and implausible unit price', () => {
    const warnings = fuelRecordWarnings(
      { date: '2026-01-02', odometer: 900, liters: 10, amount: 300, isFull: true },
      [record({ date: '2026-01-01', odometer: 1000 })],
      '2026-01-03',
    )
    expect(warnings.some((warning) => warning.includes('里程低于'))).toBe(true)
    expect(warnings.some((warning) => warning.includes('单价'))).toBe(true)
  })
})
