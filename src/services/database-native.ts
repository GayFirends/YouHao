import { CapacitorSQLite, SQLiteConnection, type SQLiteDBConnection } from '@capacitor-community/sqlite'
import type { DatabaseAdapter } from './database-adapter'
import type { FuelRecord, SyncPayload, Vehicle } from '../types'
import { shouldAcceptRemote } from './conflict-resolution'
import { listRecordPage, summarizeVehicle } from './record-query'

const DATABASE_NAME = 'fuel-track'
const DATABASE_VERSION = 4

const VEHICLE_UPSERT = `
  INSERT INTO vehicles (id, name, plate, fuelType, initialOdometer, createdAt, updatedAt, deletedAt)
  VALUES (?, ?, ?, ?, ?, ?, ?, ?)
  ON CONFLICT(id) DO UPDATE SET
    name = excluded.name, plate = excluded.plate, fuelType = excluded.fuelType,
    initialOdometer = excluded.initialOdometer, createdAt = excluded.createdAt, updatedAt = excluded.updatedAt,
    deletedAt = excluded.deletedAt
`

const RECORD_UPSERT = `
  INSERT INTO fuel_records (
    id, vehicleId, date, odometer, liters, amount, pumpAmount, pricePerLiter, isFull,
    station, note, createdAt, updatedAt, deletedAt
  ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  ON CONFLICT(id) DO UPDATE SET
    vehicleId = excluded.vehicleId, date = excluded.date, odometer = excluded.odometer,
    liters = excluded.liters, amount = excluded.amount, pumpAmount = excluded.pumpAmount,
    pricePerLiter = excluded.pricePerLiter,
    isFull = excluded.isFull, station = excluded.station, note = excluded.note,
    createdAt = excluded.createdAt, updatedAt = excluded.updatedAt, deletedAt = excluded.deletedAt
`

let connection: SQLiteDBConnection

function vehicleValues(vehicle: Vehicle) {
  return [
    vehicle.id,
    vehicle.name,
    vehicle.plate,
    vehicle.fuelType,
    vehicle.initialOdometer,
    vehicle.createdAt,
    vehicle.updatedAt,
    vehicle.deletedAt,
  ]
}

function recordValues(record: FuelRecord) {
  return [
    record.id,
    record.vehicleId,
    record.date,
    record.odometer,
    record.liters,
    record.amount,
    record.pumpAmount,
    record.pricePerLiter,
    record.isFull ? 1 : 0,
    record.station,
    record.note,
    record.createdAt,
    record.updatedAt,
    record.deletedAt,
  ]
}

function mapVehicle(row: Record<string, unknown>): Vehicle {
  return row as unknown as Vehicle
}

function mapRecord(row: Record<string, unknown>): FuelRecord {
  return { ...(row as unknown as FuelRecord), isFull: Boolean(row.isFull) }
}

async function vehicles(includeDeleted = false) {
  const result = await connection.query(`SELECT * FROM vehicles ${includeDeleted ? '' : 'WHERE deletedAt IS NULL'} ORDER BY createdAt`)
  return (result.values || []).map((row) => mapVehicle(row as Record<string, unknown>))
}

async function records(includeDeleted = false) {
  const result = await connection.query(
    `SELECT * FROM fuel_records ${includeDeleted ? '' : 'WHERE deletedAt IS NULL'} ORDER BY date DESC, odometer DESC`,
  )
  return (result.values || []).map((row) => mapRecord(row as Record<string, unknown>))
}

async function init() {
  const sqlite = new SQLiteConnection(CapacitorSQLite)
  await sqlite.addUpgradeStatement(DATABASE_NAME, [
    {
      toVersion: 1,
      statements: [
        `CREATE TABLE IF NOT EXISTS vehicles (
        id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, plate TEXT NOT NULL DEFAULT '',
        fuelType TEXT NOT NULL DEFAULT '92#', initialOdometer REAL NOT NULL DEFAULT 0,
        createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL, deletedAt TEXT
      );`,
        `CREATE TABLE IF NOT EXISTS fuel_records (
        id TEXT PRIMARY KEY NOT NULL, vehicleId TEXT NOT NULL, date TEXT NOT NULL,
        odometer REAL NOT NULL CHECK (odometer >= 0),
        liters REAL NOT NULL CHECK (liters > 0),
        amount REAL NOT NULL CHECK (amount >= 0),
        pricePerLiter REAL NOT NULL CHECK (pricePerLiter >= 0),
        isFull INTEGER NOT NULL DEFAULT 1 CHECK (isFull IN (0, 1)),
        station TEXT NOT NULL DEFAULT '', note TEXT NOT NULL DEFAULT '',
        createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL, deletedAt TEXT,
        FOREIGN KEY (vehicleId) REFERENCES vehicles(id)
      );`,
        'CREATE INDEX IF NOT EXISTS idx_records_vehicle_date ON fuel_records(vehicleId, date);',
      ],
    },
    {
      toVersion: 2,
      statements: ['CREATE INDEX IF NOT EXISTS idx_records_vehicle_date ON fuel_records(vehicleId, date);'],
    },
    {
      toVersion: 3,
      statements: [
        'ALTER TABLE fuel_records ADD COLUMN pumpAmount REAL NOT NULL DEFAULT 0 CHECK (pumpAmount >= 0);',
        'UPDATE fuel_records SET pumpAmount = amount;',
      ],
    },
    {
      toVersion: 4,
      statements: [
        `CREATE TABLE IF NOT EXISTS safety_snapshots (
        id TEXT PRIMARY KEY NOT NULL, createdAt TEXT NOT NULL, payload TEXT NOT NULL
      );`,
        'CREATE INDEX IF NOT EXISTS idx_records_vehicle_sort ON fuel_records(vehicleId, date DESC, odometer DESC, createdAt DESC, id DESC);',
        'CREATE INDEX IF NOT EXISTS idx_records_deleted ON fuel_records(deletedAt);',
        'CREATE INDEX IF NOT EXISTS idx_vehicles_deleted ON vehicles(deletedAt);',
      ],
    },
  ])

  const consistency = await sqlite.checkConnectionsConsistency()
  const existing = consistency.result ? await sqlite.isConnection(DATABASE_NAME, false) : { result: false }
  connection = existing.result
    ? await sqlite.retrieveConnection(DATABASE_NAME, false)
    : await sqlite.createConnection(DATABASE_NAME, false, 'no-encryption', DATABASE_VERSION, false)

  if (!(await connection.isDBOpen()).result) await connection.open()
  await connection.execute('PRAGMA foreign_keys = ON;', false)

  const integrity = await connection.query('PRAGMA quick_check;')
  const result = integrity.values?.[0]?.quick_check
  if (result !== 'ok') throw new Error(`Android SQLite 数据库完整性检查失败：${String(result || '未知错误')}`)

  const count = await connection.query('SELECT COUNT(*) AS count FROM vehicles;')
  if (Number(count.values?.[0]?.count || 0) === 0) {
    const now = new Date().toISOString()
    await connection.run(VEHICLE_UPSERT, [crypto.randomUUID(), '我的车辆', '', '92#', 0, now, now, null])
  }
}

export const nativeDatabase: DatabaseAdapter = {
  init,
  vehicles,
  records,
  async listRecords(query) {
    return listRecordPage(await records(true), query)
  },
  async getRecord(id) {
    const result = await connection.query('SELECT * FROM fuel_records WHERE id = ? LIMIT 1', [id])
    const row = result.values?.[0]
    return row ? mapRecord(row as Record<string, unknown>) : undefined
  },
  async getVehicleSummary(vehicleId) {
    return summarizeVehicle(await records(true), vehicleId)
  },
  async saveVehicle(vehicle) {
    await connection.run(VEHICLE_UPSERT, vehicleValues(vehicle))
  },
  async saveRecord(record) {
    await connection.run(RECORD_UPSERT, recordValues(record))
  },
  async deleteVehicle(vehicleId, deletedAt) {
    await connection.beginTransaction()
    try {
      await connection.run('UPDATE vehicles SET deletedAt = ?, updatedAt = ? WHERE id = ?', [deletedAt, deletedAt, vehicleId], false)
      await connection.run(
        'UPDATE fuel_records SET deletedAt = ?, updatedAt = ? WHERE vehicleId = ? AND deletedAt IS NULL',
        [deletedAt, deletedAt, vehicleId],
        false,
      )
      await connection.commitTransaction()
    } catch (error) {
      await connection.rollbackTransaction()
      throw error
    }
  },
  async exportData(): Promise<SyncPayload> {
    const [allVehicles, allRecords] = await Promise.all([vehicles(true), records(true)])
    return { version: 1, exportedAt: new Date().toISOString(), vehicles: allVehicles, records: allRecords }
  },
  async mergeData(remote) {
    await connection.beginTransaction()
    try {
      const localVehicles = new Map((await vehicles(true)).map((item) => [item.id, item]))
      const localRecords = new Map((await records(true)).map((item) => [item.id, item]))
      for (const item of remote.vehicles) {
        const local = localVehicles.get(item.id)
        if (shouldAcceptRemote(local, item)) await connection.run(VEHICLE_UPSERT, vehicleValues(item), false)
      }
      for (const item of remote.records) {
        const local = localRecords.get(item.id)
        if (shouldAcceptRemote(local, item)) await connection.run(RECORD_UPSERT, recordValues(item), false)
      }
      await connection.commitTransaction()
    } catch (error) {
      await connection.rollbackTransaction()
      throw error
    }
  },
  async createSafetySnapshot() {
    const id = crypto.randomUUID()
    const createdAt = new Date().toISOString()
    const [allVehicles, allRecords] = await Promise.all([vehicles(true), records(true)])
    const payload: SyncPayload = { version: 1, exportedAt: createdAt, vehicles: allVehicles, records: allRecords }
    await connection.run('INSERT INTO safety_snapshots (id, createdAt, payload) VALUES (?, ?, ?)', [id, createdAt, JSON.stringify(payload)])
    return { id, createdAt }
  },
  async restoreSafetySnapshot(id) {
    const result = await connection.query('SELECT payload FROM safety_snapshots WHERE id = ? LIMIT 1', [id])
    const text = result.values?.[0]?.payload
    if (typeof text !== 'string') throw new Error('安全快照不存在')
    const payload = JSON.parse(text) as SyncPayload
    await connection.beginTransaction()
    try {
      await connection.run('DELETE FROM fuel_records', [], false)
      await connection.run('DELETE FROM vehicles', [], false)
      for (const vehicle of payload.vehicles) await connection.run(VEHICLE_UPSERT, vehicleValues(vehicle), false)
      for (const record of payload.records) await connection.run(RECORD_UPSERT, recordValues(record), false)
      await connection.commitTransaction()
    } catch (error) {
      await connection.rollbackTransaction()
      throw error
    }
  },
  async compactTombstones(cutoff) {
    const recordsResult = await connection.query(
      'SELECT COUNT(*) AS count FROM fuel_records WHERE deletedAt IS NOT NULL AND deletedAt < ?',
      [cutoff],
    )
    const vehiclesResult = await connection.query('SELECT COUNT(*) AS count FROM vehicles WHERE deletedAt IS NOT NULL AND deletedAt < ?', [
      cutoff,
    ])
    await connection.beginTransaction()
    try {
      await connection.run('DELETE FROM fuel_records WHERE deletedAt IS NOT NULL AND deletedAt < ?', [cutoff], false)
      await connection.run('DELETE FROM vehicles WHERE deletedAt IS NOT NULL AND deletedAt < ?', [cutoff], false)
      await connection.commitTransaction()
    } catch (error) {
      await connection.rollbackTransaction()
      throw error
    }
    return Number(recordsResult.values?.[0]?.count || 0) + Number(vehiclesResult.values?.[0]?.count || 0)
  },
  async getSchemaInfo() {
    const result = await connection.query('PRAGMA user_version;')
    return { version: Number(result.values?.[0]?.user_version || DATABASE_VERSION), backend: 'android-native-sqlite' as const }
  },
  async flush() {
    // Native SQLite commits each awaited write before resolving.
  },
}
