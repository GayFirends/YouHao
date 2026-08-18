import initSqlJs, { type Database, type SqlValue } from 'sql.js'
import wasmUrl from 'sql.js/dist/sql-wasm.wasm?url'
import type { DatabaseAdapter } from './database-adapter'
import type { FuelRecord, SyncPayload, Vehicle } from '../types'
import { shouldAcceptRemote } from './conflict-resolution'

const LEGACY_DB_KEY = 'fuel-track-sqlite-v1'
const STORAGE_NAME = 'fuel-track-storage'
const STORAGE_VERSION = 1
const STORAGE_STORE = 'databases'
const STORAGE_KEY = 'main'
const SCHEMA_VERSION = 3

let db: Database
let storagePromise: Promise<IDBDatabase> | undefined
let persistenceQueue = Promise.resolve()

function openStorage(): Promise<IDBDatabase> {
  if (!('indexedDB' in globalThis)) return Promise.reject(new Error('当前环境不支持 IndexedDB'))
  if (storagePromise) return storagePromise

  storagePromise = new Promise((resolve, reject) => {
    const request = indexedDB.open(STORAGE_NAME, STORAGE_VERSION)
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains(STORAGE_STORE)) request.result.createObjectStore(STORAGE_STORE)
    }
    request.onsuccess = () => {
      request.result.onversionchange = () => request.result.close()
      resolve(request.result)
    }
    request.onerror = () => reject(request.error || new Error('无法打开本地数据库存储'))
    request.onblocked = () => reject(new Error('数据库升级被其他页面阻止，请关闭其他油迹页面后重试'))
  })
  return storagePromise
}

async function readDatabaseBytes(): Promise<Uint8Array | null> {
  const storage = await openStorage()
  return new Promise((resolve, reject) => {
    const transaction = storage.transaction(STORAGE_STORE, 'readonly')
    const request = transaction.objectStore(STORAGE_STORE).get(STORAGE_KEY)
    request.onsuccess = () => {
      const value = request.result
      if (!value) resolve(null)
      else if (value instanceof Uint8Array) resolve(value)
      else if (value instanceof ArrayBuffer) resolve(new Uint8Array(value))
      else reject(new Error('本地数据库文件格式无效'))
    }
    request.onerror = () => reject(request.error || new Error('读取本地数据库失败'))
  })
}

async function writeDatabaseBytes(bytes: Uint8Array): Promise<void> {
  const storage = await openStorage()
  await new Promise<void>((resolve, reject) => {
    const transaction = storage.transaction(STORAGE_STORE, 'readwrite', { durability: 'strict' })
    transaction.objectStore(STORAGE_STORE).put(bytes, STORAGE_KEY)
    transaction.oncomplete = () => resolve()
    transaction.onerror = () => reject(transaction.error || new Error('保存本地数据库失败'))
    transaction.onabort = () => reject(transaction.error || new Error('保存本地数据库已中止'))
  })
}

function legacyDatabaseBytes(): Uint8Array | null {
  const stored = localStorage.getItem(LEGACY_DB_KEY)
  if (!stored) return null
  try {
    const binary = atob(stored)
    return Uint8Array.from(binary, (character) => character.charCodeAt(0))
  } catch {
    throw new Error('旧版数据库数据已损坏，无法自动迁移')
  }
}

function persist(): Promise<void> {
  const snapshot = db.export().slice()
  const operation = persistenceQueue.then(() => writeDatabaseBytes(snapshot))
  persistenceQueue = operation.catch(() => undefined)
  return operation
}

function rows<T>(sql: string, params: SqlValue[] = []): T[] {
  const statement = db.prepare(sql)
  try {
    statement.bind(params)
    const output: T[] = []
    while (statement.step()) output.push(statement.getAsObject() as T)
    return output
  } finally {
    statement.free()
  }
}

function runTransaction(work: () => void) {
  db.run('BEGIN IMMEDIATE')
  try {
    work()
    db.run('COMMIT')
  } catch (error) {
    db.run('ROLLBACK')
    throw error
  }
}

function upsertVehicle(vehicle: Vehicle) {
  db.run(`
    INSERT INTO vehicles (id, name, plate, fuelType, initialOdometer, createdAt, updatedAt, deletedAt)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(id) DO UPDATE SET
      name = excluded.name, plate = excluded.plate, fuelType = excluded.fuelType,
      initialOdometer = excluded.initialOdometer, updatedAt = excluded.updatedAt,
      deletedAt = excluded.deletedAt
  `, [vehicle.id, vehicle.name, vehicle.plate, vehicle.fuelType, vehicle.initialOdometer, vehicle.createdAt, vehicle.updatedAt, vehicle.deletedAt])
}

function upsertRecord(record: FuelRecord) {
  db.run(`
    INSERT INTO fuel_records (
      id, vehicleId, date, odometer, liters, amount, pumpAmount, pricePerLiter, isFull,
      station, note, createdAt, updatedAt, deletedAt
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(id) DO UPDATE SET
      vehicleId = excluded.vehicleId, date = excluded.date, odometer = excluded.odometer,
      liters = excluded.liters, amount = excluded.amount, pumpAmount = excluded.pumpAmount,
      pricePerLiter = excluded.pricePerLiter,
      isFull = excluded.isFull, station = excluded.station, note = excluded.note,
      updatedAt = excluded.updatedAt, deletedAt = excluded.deletedAt
  `, [record.id, record.vehicleId, record.date, record.odometer, record.liters, record.amount, record.pumpAmount, record.pricePerLiter, record.isFull ? 1 : 0, record.station, record.note, record.createdAt, record.updatedAt, record.deletedAt])
}

function migrateSchema() {
  const version = Number(rows<{ user_version: number }>('PRAGMA user_version')[0]?.user_version || 0)
  if (version > SCHEMA_VERSION) throw new Error('本地数据库由更高版本的油迹创建，请升级应用')

  runTransaction(() => {
    if (version < 1) {
      db.run(`
        CREATE TABLE IF NOT EXISTS vehicles (
          id TEXT PRIMARY KEY, name TEXT NOT NULL, plate TEXT NOT NULL DEFAULT '',
          fuelType TEXT NOT NULL DEFAULT '92#', initialOdometer REAL NOT NULL DEFAULT 0,
          createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL, deletedAt TEXT
        );
        CREATE TABLE IF NOT EXISTS fuel_records (
          id TEXT PRIMARY KEY, vehicleId TEXT NOT NULL, date TEXT NOT NULL,
          odometer REAL NOT NULL CHECK (odometer >= 0),
          liters REAL NOT NULL CHECK (liters > 0),
          amount REAL NOT NULL CHECK (amount >= 0),
          pricePerLiter REAL NOT NULL CHECK (pricePerLiter >= 0),
          isFull INTEGER NOT NULL DEFAULT 1 CHECK (isFull IN (0, 1)),
          station TEXT NOT NULL DEFAULT '', note TEXT NOT NULL DEFAULT '',
          createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL, deletedAt TEXT
        );
        CREATE INDEX IF NOT EXISTS idx_records_vehicle_date ON fuel_records(vehicleId, date);
        PRAGMA user_version = 1;
      `)
    }
    if (version < 2) {
      db.run(`
        CREATE TABLE fuel_records_v2 (
          id TEXT PRIMARY KEY, vehicleId TEXT NOT NULL, date TEXT NOT NULL,
          odometer REAL NOT NULL CHECK (odometer >= 0),
          liters REAL NOT NULL CHECK (liters > 0),
          amount REAL NOT NULL CHECK (amount >= 0),
          pricePerLiter REAL NOT NULL CHECK (pricePerLiter >= 0),
          isFull INTEGER NOT NULL DEFAULT 1 CHECK (isFull IN (0, 1)),
          station TEXT NOT NULL DEFAULT '', note TEXT NOT NULL DEFAULT '',
          createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL, deletedAt TEXT,
          FOREIGN KEY (vehicleId) REFERENCES vehicles(id)
        );
        INSERT INTO fuel_records_v2 SELECT * FROM fuel_records;
        DROP TABLE fuel_records;
        ALTER TABLE fuel_records_v2 RENAME TO fuel_records;
        CREATE INDEX idx_records_vehicle_date ON fuel_records(vehicleId, date);
        PRAGMA user_version = 2;
      `)
    }
    if (version < 3) {
      db.run(`
        ALTER TABLE fuel_records ADD COLUMN pumpAmount REAL NOT NULL DEFAULT 0 CHECK (pumpAmount >= 0);
        UPDATE fuel_records SET pumpAmount = amount;
        PRAGMA user_version = 3;
      `)
    }
  })
  db.run('PRAGMA foreign_keys = ON')
}

function assertDatabaseIntegrity() {
  const result = rows<{ quick_check: string }>('PRAGMA quick_check')[0]?.quick_check
  if (result !== 'ok') throw new Error(`本地 SQLite 数据库完整性检查失败：${result || '未知错误'}`)
  const foreignKeyErrors = rows<Record<string, unknown>>('PRAGMA foreign_key_check')
  if (foreignKeyErrors.length) throw new Error('本地数据库存在引用不到车辆的加油记录，请从备份恢复或联系维护者')
}

async function init() {
  const SQL = await initSqlJs({ locateFile: () => wasmUrl })
  const indexedDbBytes = await readDatabaseBytes()
  const legacyBytes = indexedDbBytes ? null : legacyDatabaseBytes()
  const storedBytes = indexedDbBytes || legacyBytes

  try {
    db = storedBytes ? new SQL.Database(storedBytes) : new SQL.Database()
    if (storedBytes) assertDatabaseIntegrity()
    migrateSchema()
    assertDatabaseIntegrity()
  } catch (error) {
    db?.close()
    throw error
  }

  if (!storedBytes) {
    const now = new Date().toISOString()
    upsertVehicle({ id: crypto.randomUUID(), name: '我的车辆', plate: '', fuelType: '92#', initialOdometer: 0, createdAt: now, updatedAt: now, deletedAt: null })
  }

  await persist()
  if (legacyBytes) localStorage.removeItem(LEGACY_DB_KEY)
}

function mapVehicle(row: Record<string, unknown>): Vehicle {
  return row as unknown as Vehicle
}

function mapRecord(row: Record<string, unknown>): FuelRecord {
  return { ...(row as unknown as FuelRecord), isFull: Boolean(row.isFull) }
}

function getVehicles(includeDeleted = false) {
  return rows<Record<string, unknown>>(`SELECT * FROM vehicles ${includeDeleted ? '' : 'WHERE deletedAt IS NULL'} ORDER BY createdAt`).map(mapVehicle)
}

function getRecords(includeDeleted = false) {
  return rows<Record<string, unknown>>(`SELECT * FROM fuel_records ${includeDeleted ? '' : 'WHERE deletedAt IS NULL'} ORDER BY date DESC, odometer DESC`).map(mapRecord)
}

export const webDatabase: DatabaseAdapter = {
  init,
  async vehicles(includeDeleted = false) {
    return getVehicles(includeDeleted)
  },
  async records(includeDeleted = false) {
    return getRecords(includeDeleted)
  },
  async saveVehicle(vehicle: Vehicle) {
    runTransaction(() => upsertVehicle(vehicle))
    await persist()
  },
  async saveRecord(record: FuelRecord) {
    runTransaction(() => upsertRecord(record))
    await persist()
  },
  async deleteVehicle(vehicleId: string, deletedAt: string) {
    runTransaction(() => {
      db.run('UPDATE vehicles SET deletedAt = ?, updatedAt = ? WHERE id = ?', [deletedAt, deletedAt, vehicleId])
      db.run('UPDATE fuel_records SET deletedAt = ?, updatedAt = ? WHERE vehicleId = ? AND deletedAt IS NULL', [deletedAt, deletedAt, vehicleId])
    })
    await persist()
  },
  async exportData(): Promise<SyncPayload> {
    return { version: 1, exportedAt: new Date().toISOString(), vehicles: getVehicles(true), records: getRecords(true) }
  },
  async mergeData(remote: SyncPayload) {
    runTransaction(() => {
      const localVehicles = new Map(getVehicles(true).map((item) => [item.id, item]))
      const localRecords = new Map(getRecords(true).map((item) => [item.id, item]))
      for (const item of remote.vehicles) {
        const local = localVehicles.get(item.id)
        if (shouldAcceptRemote(local, item)) upsertVehicle(item)
      }
      for (const item of remote.records) {
        const local = localRecords.get(item.id)
        if (shouldAcceptRemote(local, item)) upsertRecord(item)
      }
    })
    await persist()
  },
  async flush() {
    await persistenceQueue
  },
}
