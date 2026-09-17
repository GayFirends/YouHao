import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Vehicle } from '../../types'

const { adapter } = vi.hoisted(() => ({
  adapter: {
    init: vi.fn(async () => undefined),
    saveVehicle: vi.fn(async (_vehicle: Vehicle): Promise<void> => undefined),
    mergeData: vi.fn(async () => undefined),
    exportData: vi.fn(async () => ({ version: 1 as const, exportedAt: '', vehicles: [], records: [] })),
  },
}))

vi.mock('@capacitor/core', () => ({ Capacitor: { isNativePlatform: () => true } }))
vi.mock('../database-native', () => ({ nativeDatabase: adapter }))

import { database, initDatabase } from '../database'

const vehicle: Vehicle = { id: 'car', name: '车辆', plate: '', fuelType: '92#', initialOdometer: 0, createdAt: '', updatedAt: '', deletedAt: null }

beforeEach(async () => {
  vi.clearAllMocks()
  await initDatabase()
})

describe('database operation scheduling', () => {
  it('waits for a complete adapter operation before starting a native merge or export', async () => {
    let finish!: () => void
    adapter.saveVehicle.mockImplementationOnce(() => new Promise<void>((resolve) => { finish = resolve }))
    const write = database.saveVehicle(vehicle)
    const merge = database.mergeData({ version: 1, exportedAt: '', vehicles: [], records: [] })
    const snapshot = database.exportData()
    await Promise.resolve()
    expect(adapter.saveVehicle).toHaveBeenCalledOnce()
    expect(adapter.mergeData).not.toHaveBeenCalled()
    expect(adapter.exportData).not.toHaveBeenCalled()
    finish()
    await Promise.all([write, merge, snapshot])
    expect(adapter.mergeData).toHaveBeenCalledOnce()
    expect(adapter.exportData).toHaveBeenCalledOnce()
    expect(adapter.mergeData.mock.invocationCallOrder[0]).toBeLessThan(adapter.exportData.mock.invocationCallOrder[0])
  })

  it('allows retries after a failed adapter operation', async () => {
    adapter.saveVehicle.mockRejectedValueOnce(new Error('保存失败'))
    await expect(database.saveVehicle(vehicle)).rejects.toThrow('保存失败')
    await database.saveVehicle(vehicle)
    await database.exportData()
    expect(adapter.saveVehicle).toHaveBeenCalledTimes(2)
    expect(adapter.exportData).toHaveBeenCalledOnce()
  })
})
