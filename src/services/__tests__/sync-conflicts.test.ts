import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { FuelRecord, SyncPayload, Vehicle } from '../../types'
import { detectSyncConflicts, saveSyncBase, withoutConflicts } from '../sync-conflicts'

const target = 'https://dav.example.com/fuel/data.json'
const vehicle: Vehicle = {
  id: 'car',
  name: '车辆',
  plate: '',
  fuelType: '92#',
  initialOdometer: 0,
  createdAt: '2026-01-01T00:00:00.000Z',
  updatedAt: '2026-01-01T00:00:00.000Z',
  deletedAt: null,
}
const record: FuelRecord = {
  id: 'record',
  vehicleId: 'car',
  date: '2026-01-01',
  odometer: 100,
  liters: 10,
  amount: 70,
  pumpAmount: 70,
  pricePerLiter: 7,
  isFull: true,
  station: '',
  note: '',
  createdAt: '2026-01-01T00:00:00.000Z',
  updatedAt: '2026-01-01T00:00:00.000Z',
  deletedAt: null,
}

function payload(vehicleValue = vehicle, recordValue = record): SyncPayload {
  return { version: 1, exportedAt: '2026-01-01T00:00:00.000Z', vehicles: [vehicleValue], records: [recordValue] }
}

describe('three-way sync conflict detection', () => {
  beforeEach(() => {
    const values = new Map<string, string>()
    vi.stubGlobal('localStorage', {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value),
    })
  })

  it('does not report conflicts without a common sync base', async () => {
    expect(await detectSyncConflicts(payload({ ...vehicle, name: '本机' }), payload({ ...vehicle, name: '云端' }), target)).toEqual([])
  })

  it('only reports entities changed differently on both sides', async () => {
    await saveSyncBase(payload(), target)
    expect(await detectSyncConflicts(payload({ ...vehicle, name: '本机' }), payload(), target)).toEqual([])
    expect(await detectSyncConflicts(payload(), payload({ ...vehicle, name: '云端' }), target)).toEqual([])
    expect(await detectSyncConflicts(payload({ ...vehicle, name: '相同' }), payload({ ...vehicle, name: '相同' }), target)).toEqual([])

    const conflicts = await detectSyncConflicts(
      payload({ ...vehicle, name: '本机' }, { ...record, note: '本机' }),
      payload({ ...vehicle, name: '云端' }, { ...record, note: '云端' }),
      target,
    )
    expect(conflicts.map((item) => item.id)).toEqual(['vehicle:car', 'record:record'])
    expect(withoutConflicts(payload(), conflicts)).toMatchObject({ vehicles: [], records: [] })
  })

  it('isolates bases by synchronization target and ignores malformed storage', async () => {
    await saveSyncBase(payload(), target)
    expect(
      await detectSyncConflicts(payload({ ...vehicle, name: '本机' }), payload({ ...vehicle, name: '云端' }), `${target}-other`),
    ).toEqual([])
    localStorage.setItem(`fuel-track-sync-base-v1:${encodeURIComponent(target)}`, '{broken')
    expect(await detectSyncConflicts(payload({ ...vehicle, name: '本机' }), payload({ ...vehicle, name: '云端' }), target)).toEqual([])
  })
})
