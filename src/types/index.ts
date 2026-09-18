export interface Vehicle {
  id: string
  name: string
  plate: string
  fuelType: string
  initialOdometer: number
  createdAt: string
  updatedAt: string
  deletedAt: string | null
}

export interface FuelRecord {
  id: string
  vehicleId: string
  date: string
  odometer: number
  liters: number
  amount: number
  pumpAmount: number
  pricePerLiter: number
  isFull: boolean
  station: string
  note: string
  createdAt: string
  updatedAt: string
  deletedAt: string | null
}

export interface WebDavConfig {
  url: string
  username: string
  password: string
  fileName: string
  encryptionEnabled?: boolean
  encryptionPassphrase?: string
  rememberEncryptionPassphrase?: boolean
}

export interface SyncPayloadV1 {
  version: 1
  exportedAt: string
  vehicles: Vehicle[]
  records: FuelRecord[]
}

/** Backwards-compatible name used by v1 backups and the local database. */
export type SyncPayload = SyncPayloadV1

export interface EncryptedSyncEnvelopeV2 {
  version: 2
  encrypted: true
  createdAt: string
  crypto: {
    algorithm: 'AES-GCM'
    kdf: 'PBKDF2-SHA-256'
    iterations: number
    salt: string
    iv: string
  }
  compression: 'gzip'
  ciphertext: string
}

export type SyncDocument = SyncPayloadV1 | EncryptedSyncEnvelopeV2

export interface RecordCursor {
  date: string
  odometer: number
  createdAt: string
  id: string
}

export interface RecordListQuery {
  vehicleId?: string
  includeDeleted?: boolean
  search?: string
  month?: string
  limit?: number
  cursor?: RecordCursor
}

export interface RecordPage {
  items: FuelRecord[]
  nextCursor: RecordCursor | null
}

export interface VehicleSummary {
  vehicleId: string
  recordCount: number
  totalPaid: number
  currentOdometer: number | null
}

export interface SafetySnapshot {
  id: string
  createdAt: string
}

export interface SchemaInfo {
  version: number
  backend: 'web-sqlite-wasm' | 'android-native-sqlite'
}

export interface SyncConflict {
  id: string
  entityType: 'vehicle' | 'record'
  entityId: string
  localValue: Vehicle | FuelRecord
  remoteValue: Vehicle | FuelRecord
  detectedAt: string
}

export interface SyncDeviceState {
  deviceId: string
  lastSeenAt: string
  acknowledgedThrough: string
}

export interface SyncMetadata {
  version: 1
  devices: Record<string, SyncDeviceState>
}

export type ViewName = 'overview' | 'records' | 'vehicles' | 'settings'
