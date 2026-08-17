import { Capacitor } from '@capacitor/core'
import type { DatabaseAdapter } from './database-adapter'
import type { FuelRecord, SyncPayload, Vehicle, WebDavConfig } from '../types'

const CONFIG_KEY = 'fuel-track-webdav-config'
const PASSWORD_KEY = 'fuel-track-webdav-password'
let adapter: DatabaseAdapter

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
    return activeAdapter().vehicles(includeDeleted)
  },
  records(includeDeleted = false): Promise<FuelRecord[]> {
    return activeAdapter().records(includeDeleted)
  },
  saveVehicle(vehicle: Vehicle) {
    return activeAdapter().saveVehicle(vehicle)
  },
  saveRecord(record: FuelRecord) {
    return activeAdapter().saveRecord(record)
  },
  deleteVehicle(vehicleId: string, deletedAt: string) {
    return activeAdapter().deleteVehicle(vehicleId, deletedAt)
  },
  exportData(): Promise<SyncPayload> {
    return activeAdapter().exportData()
  },
  mergeData(remote: SyncPayload) {
    return activeAdapter().mergeData(remote)
  },
  flush() {
    return activeAdapter().flush()
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
