import type { FuelRecord, SyncConflict, SyncPayload, Vehicle } from '../types'

const BASE_KEY = 'fuel-track-sync-base-v1'

interface SyncBase {
  vehicles: Record<string, string>
  records: Record<string, string>
}

function canonical(value: Vehicle | FuelRecord) {
  return JSON.stringify(Object.fromEntries(Object.entries(value).sort(([left], [right]) => left.localeCompare(right))))
}

async function hash(value: Vehicle | FuelRecord) {
  const bytes = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(canonical(value)))
  return Array.from(new Uint8Array(bytes), (byte) => byte.toString(16).padStart(2, '0')).join('')
}

function storageKey(target = 'default') {
  return `${BASE_KEY}:${encodeURIComponent(target)}`
}

function readBase(target?: string): SyncBase {
  try {
    const value = JSON.parse(localStorage.getItem(storageKey(target)) || '{"vehicles":{},"records":{}}') as SyncBase
    if (!value.vehicles || typeof value.vehicles !== 'object' || !value.records || typeof value.records !== 'object')
      throw new Error('invalid base')
    return value
  } catch {
    return { vehicles: {}, records: {} }
  }
}

export async function saveSyncBase(payload: SyncPayload, target?: string) {
  const base: SyncBase = { vehicles: {}, records: {} }
  await Promise.all(
    payload.vehicles.map(async (item) => {
      base.vehicles[item.id] = await hash(item)
    }),
  )
  await Promise.all(
    payload.records.map(async (item) => {
      base.records[item.id] = await hash(item)
    }),
  )
  localStorage.setItem(storageKey(target), JSON.stringify(base))
}

export async function detectSyncConflicts(local: SyncPayload, remote: SyncPayload, target?: string) {
  const base = readBase(target)
  const conflicts: SyncConflict[] = []
  const detectedAt = new Date().toISOString()

  async function compare(entityType: 'vehicle' | 'record', localItems: (Vehicle | FuelRecord)[], remoteItems: (Vehicle | FuelRecord)[]) {
    const localMap = new Map(localItems.map((item) => [item.id, item]))
    for (const remoteValue of remoteItems) {
      const localValue = localMap.get(remoteValue.id)
      const baseHash = (entityType === 'vehicle' ? base.vehicles : base.records)[remoteValue.id]
      if (!localValue || !baseHash) continue
      const [localHash, remoteHash] = await Promise.all([hash(localValue), hash(remoteValue)])
      if (localHash !== baseHash && remoteHash !== baseHash && localHash !== remoteHash) {
        conflicts.push({
          id: `${entityType}:${remoteValue.id}`,
          entityType,
          entityId: remoteValue.id,
          localValue,
          remoteValue,
          detectedAt,
        })
      }
    }
  }

  await compare('vehicle', local.vehicles, remote.vehicles)
  await compare('record', local.records, remote.records)
  return conflicts
}

export function withoutConflicts(payload: SyncPayload, conflicts: SyncConflict[]): SyncPayload {
  const vehicles = new Set(conflicts.filter((item) => item.entityType === 'vehicle').map((item) => item.entityId))
  const records = new Set(conflicts.filter((item) => item.entityType === 'record').map((item) => item.entityId))
  return {
    ...payload,
    vehicles: payload.vehicles.filter((item) => !vehicles.has(item.id)),
    records: payload.records.filter((item) => !records.has(item.id)),
  }
}
