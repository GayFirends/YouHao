import { Capacitor } from '@capacitor/core'
import type { DatabaseAdapter } from './database-adapter'
import type { FuelRecord, SyncPayload, Vehicle, WebDavConfig } from '../types'

const CONFIG_KEY = 'fuel-track-webdav-config'
const PASSWORD_KEY = 'fuel-track-webdav-password'
let adapter: DatabaseAdapter
let operationQueue = Promise.resolve()

function enqueue<T>(work: () => Promise<T>): Promise<T> {
  const operation = operationQueue.then(work)
  // A failed operation must not prevent later retries. Keeping whole adapter
  // calls in the queue also prevents overlapping native SQLite transactions.
  operationQueue = operation.then(() => undefined, () => undefined)
  return operation
}

function activeAdapter() {
  if (!adapter) throw new Error('数据库尚未初始化')
  return adapter
}

export async function initDatabase() {
  if (Capacitor.isNativePlatform()) {
    adapter = (await import('./database-native')).nativeDatabase
  } else {
    adapter = (await import('./database-web')).webDatabase
  }
  await adapter.init()
}

export const database = {
  vehicles(includeDeleted = false): Promise<Vehicle[]> {
    return enqueue(() => activeAdapter().vehicles(includeDeleted))
  },
  records(includeDeleted = false): Promise<FuelRecord[]> {
    return enqueue(() => activeAdapter().records(includeDeleted))
  },
  saveVehicle(vehicle: Vehicle) {
    return enqueue(() => activeAdapter().saveVehicle(vehicle))
  },
  saveRecord(record: FuelRecord) {
    return enqueue(() => activeAdapter().saveRecord(record))
  },
  deleteVehicle(vehicleId: string, deletedAt: string) {
    return enqueue(() => activeAdapter().deleteVehicle(vehicleId, deletedAt))
  },
  exportData(): Promise<SyncPayload> {
    return enqueue(() => activeAdapter().exportData())
  },
  mergeData(remote: SyncPayload) {
    return enqueue(() => activeAdapter().mergeData(remote))
  },
  flush() {
    return enqueue(() => activeAdapter().flush())
  },
  getConfig(): WebDavConfig {
    const defaults = { url: '', username: '', password: '', fileName: 'fuel-track.json' }
    try {
      const stored = JSON.parse(localStorage.getItem(CONFIG_KEY) || '{}') as Partial<WebDavConfig>
      if (stored.password) {
        sessionStorage.setItem(PASSWORD_KEY, stored.password)
        delete stored.password
        localStorage.setItem(CONFIG_KEY, JSON.stringify(stored))
      }
      return { ...defaults, ...stored, password: sessionStorage.getItem(PASSWORD_KEY) || '' }
    } catch { return defaults }
  },
  saveConfig(config: WebDavConfig) {
    const { password, ...persisted } = config
    localStorage.setItem(CONFIG_KEY, JSON.stringify(persisted))
    if (password) sessionStorage.setItem(PASSWORD_KEY, password)
    else sessionStorage.removeItem(PASSWORD_KEY)
  },
}
