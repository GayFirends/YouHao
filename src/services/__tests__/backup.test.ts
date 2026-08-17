import { describe, expect, it } from 'vitest'
import { parseBackup, recordsToCsv } from '../backup'
import type { FuelRecord, Vehicle } from '../../types'

describe('backup helpers', () => {
  it('rejects unsupported JSON data', () => {
    expect(() => parseBackup('{"version":2}')).toThrow('仅支持版本 1')
  })

  it('rejects malformed records and dangling vehicle references', () => {
    expect(() => parseBackup(JSON.stringify({
      version: 1, exportedAt: '2026-01-01T00:00:00.000Z',
      vehicles: [], records: [{ id: 'r1', vehicleId: 'missing' }],
    }))).toThrow('校验失败')
  })

  it('escapes commas and quotes in CSV', () => {
    const vehicle = { id: 'v1', name: '家庭,用车', plate: '', fuelType: '92#', initialOdometer: 0, createdAt: '', updatedAt: '', deletedAt: null } satisfies Vehicle
    const record = { id: 'r1', vehicleId: 'v1', date: '2026-01-01', odometer: 100, liters: 10, amount: 75, pricePerLiter: 7.5, isFull: true, station: '测试"站', note: '', createdAt: '', updatedAt: '', deletedAt: null } satisfies FuelRecord
    const csv = recordsToCsv([record], [vehicle])
    expect(csv).toContain('"家庭,用车"')
    expect(csv).toContain('"测试""站"')
  })
})
