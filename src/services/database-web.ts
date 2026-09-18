import initSqlJs, { type Database, type SqlJsStatic, type SqlValue } from 'sql.js'
import wasmUrl from 'sql.js/dist/sql-wasm.wasm?url'
import type { DatabaseAdapter } from './database-adapter'
import type { FuelRecord, SyncConflict, SyncPayload, Vehicle } from '../types'
import { shouldAcceptRemote } from './conflict-resolution'
import { createDatabaseStorage, SnapshotConflictError } from './database-storage'
import { listRecordPage, summarizeVehicle } from './record-query'
import { AppError } from './app-error'

const LEGACY_DB_KEY = 'fuel-track-sqlite-v1'
const SCHEMA_VERSION = 5
const MAX_WRITE_ATTEMPTS = 5

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

function rows<T>(db: Database, sql: string, params: SqlValue[] = []): T[] {
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

function runTransaction(db: Database, work: () => void) {
  db.run('BEGIN IMMEDIATE')
  try {
    work()
    db.run('COMMIT')
  } catch (error) {
    db.run('ROLLBACK')
    throw error
  }
}

function upsertVehicle(db: Database, vehicle: Vehicle) {
  db.run(
    `
    INSERT INTO vehicles (id, name, plate, fuelType, initialOdometer, createdAt, updatedAt, deletedAt)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(id) DO UPDATE SET
      name = excluded.name, plate = excluded.plate, fuelType = excluded.fuelType,
      initialOdometer = excluded.initialOdometer, createdAt = excluded.createdAt, updatedAt = excluded.updatedAt,
      deletedAt = excluded.deletedAt
  `,
    [
      vehicle.id,
      vehicle.name,
      vehicle.plate,
      vehicle.fuelType,
      vehicle.initialOdometer,
      vehicle.createdAt,
      vehicle.updatedAt,
      vehicle.deletedAt,
    ],
  )
}

function upsertRecord(db: Database, record: FuelRecord) {
  db.run(
    `
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
  `,
    [
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
    ],
  )
}

function migrateSchema(db: Database) {
  const version = Number(rows<{ user_version: number }>(db, 'PRAGMA user_version')[0]?.user_version || 0)
  if (version > SCHEMA_VERSION) throw new Error('本地数据库由更高版本的油迹创建，请升级应用')

  runTransaction(db, () => {
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
    if (version < 4) {
      db.run(`
        CREATE INDEX IF NOT EXISTS idx_records_vehicle_sort ON fuel_records(vehicleId, date DESC, odometer DESC, createdAt DESC, id DESC);
        CREATE INDEX IF NOT EXISTS idx_records_deleted ON fuel_records(deletedAt);
        CREATE INDEX IF NOT EXISTS idx_vehicles_deleted ON vehicles(deletedAt);
        PRAGMA user_version = 4;
      `)
    }
    if (version < 5) {
      db.run(`
        CREATE TABLE IF NOT EXISTS sync_conflicts (
          id TEXT PRIMARY KEY, entityType TEXT NOT NULL CHECK (entityType IN ('vehicle', 'record')),
          entityId TEXT NOT NULL, localJson TEXT NOT NULL, remoteJson TEXT NOT NULL, detectedAt TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_sync_conflicts_detected ON sync_conflicts(detectedAt DESC);
        PRAGMA user_version = 5;
      `)
    }
  })
  db.run('PRAGMA foreign_keys = ON')
}

function assertDatabaseIntegrity(db: Database) {
  const result = rows<{ quick_check: string }>(db, 'PRAGMA quick_check')[0]?.quick_check
  if (result !== 'ok') throw new Error(`本地 SQLite 数据库完整性检查失败：${result || '未知错误'}`)
  const foreignKeyErrors = rows<Record<string, unknown>>(db, 'PRAGMA foreign_key_check')
  if (foreignKeyErrors.length) throw new Error('本地数据库存在引用不到车辆的加油记录，请从备份恢复或联系维护者')
}

function mapVehicle(row: Record<string, unknown>): Vehicle {
  return row as unknown as Vehicle
}

function mapRecord(row: Record<string, unknown>): FuelRecord {
  return { ...(row as unknown as FuelRecord), isFull: Boolean(row.isFull) }
}

function getVehicles(db: Database, includeDeleted = false) {
  return rows<Record<string, unknown>>(
    db,
    `SELECT * FROM vehicles ${includeDeleted ? '' : 'WHERE deletedAt IS NULL'} ORDER BY createdAt`,
  ).map(mapVehicle)
}

function getRecords(db: Database, includeDeleted = false) {
  return rows<Record<string, unknown>>(
    db,
    `SELECT * FROM fuel_records ${includeDeleted ? '' : 'WHERE deletedAt IS NULL'} ORDER BY date DESC, odometer DESC`,
  ).map(mapRecord)
}

export function createWebDatabase(loadSql: () => Promise<SqlJsStatic> = () => initSqlJs({ locateFile: () => wasmUrl })) {
  const storage = createDatabaseStorage()
  let SQL: SqlJsStatic | undefined
  let db: Database | undefined
  let loadedRevision = -1
  let queue = Promise.resolve()
  let lastWrite = Promise.resolve()

  function enqueue<T>(work: () => Promise<T>): Promise<T> {
    const operation = queue.then(work)
    queue = operation.then(
      () => undefined,
      () => undefined,
    )
    return operation
  }

  function openDatabase(bytes: Uint8Array | null) {
    if (!SQL) throw new Error('数据库尚未初始化')
    const candidate = bytes ? new SQL.Database(bytes) : new SQL.Database()
    candidate.run('PRAGMA foreign_keys = ON')
    return candidate
  }

  function useDatabase(candidate: Database, revision: number) {
    db?.close()
    db = candidate
    loadedRevision = revision
  }

  async function refresh() {
    const snapshot = await storage.read()
    if (!snapshot.bytes) throw new Error('本地数据库不存在，请刷新后重试')
    if (!db || loadedRevision !== snapshot.revision) useDatabase(openDatabase(snapshot.bytes), snapshot.revision)
    return db!
  }

  async function commit(work: (candidate: Database, hasBytes: boolean) => void, initializing = false) {
    for (let attempt = 0; attempt < MAX_WRITE_ATTEMPTS; attempt += 1) {
      const snapshot = await storage.read()
      const legacyBytes = initializing && !snapshot.bytes ? legacyDatabaseBytes() : null
      const bytes = snapshot.bytes || legacyBytes
      if (!initializing && !bytes) throw new Error('本地数据库不存在，请刷新后重试')
      const candidate = openDatabase(bytes)
      let revision: number
      try {
        work(candidate, Boolean(bytes))
        const exported = candidate.export().slice()
        // sql.js export closes/reopens its connection, resetting connection pragmas.
        candidate.run('PRAGMA foreign_keys = ON')
        revision = await storage.write(exported, snapshot.revision)
      } catch (error) {
        candidate.close()
        if (error instanceof SnapshotConflictError) continue
        throw error
      }
      // Readers only see a candidate after its entire IndexedDB transaction commits.
      useDatabase(candidate, revision)
      if (legacyBytes) localStorage.removeItem(LEGACY_DB_KEY)
      return
    }
    throw new Error('其他页面正在频繁保存数据，请稍后重试')
  }

  function mutate(work: (candidate: Database) => void) {
    lastWrite = enqueue(() => commit((candidate) => runTransaction(candidate, () => work(candidate))))
    return lastWrite
  }

  const adapter: DatabaseAdapter & { close(): Promise<void> } = {
    init() {
      lastWrite = enqueue(async () => {
        SQL = await loadSql()
        const before = await storage.read()
        if (before.bytes) await storage.createSnapshot(before.bytes, `pre-migration-${before.revision}`)
        try {
          await commit((candidate, hasBytes) => {
            if (hasBytes) assertDatabaseIntegrity(candidate)
            migrateSchema(candidate)
            assertDatabaseIntegrity(candidate)
            if (!hasBytes) {
              const now = new Date().toISOString()
              upsertVehicle(candidate, {
                id: crypto.randomUUID(),
                name: '我的车辆',
                plate: '',
                fuelType: '92#',
                initialOdometer: 0,
                createdAt: now,
                updatedAt: now,
                deletedAt: null,
              })
            }
          }, true)
        } catch (error) {
          throw new AppError('DATABASE_MIGRATION_FAILED', error instanceof Error ? error.message : '数据库升级失败', { cause: error })
        }
      })
      return lastWrite
    },
    vehicles(includeDeleted = false) {
      return enqueue(async () => getVehicles(await refresh(), includeDeleted))
    },
    records(includeDeleted = false) {
      return enqueue(async () => getRecords(await refresh(), includeDeleted))
    },
    listRecords(query) {
      return enqueue(async () => listRecordPage(getRecords(await refresh(), true), query))
    },
    getRecord(id) {
      return enqueue(async () => getRecords(await refresh(), true).find((item) => item.id === id))
    },
    getVehicleSummary(vehicleId) {
      return enqueue(async () => summarizeVehicle(getRecords(await refresh(), true), vehicleId))
    },
    saveVehicle(vehicle) {
      return mutate((candidate) => upsertVehicle(candidate, vehicle))
    },
    saveRecord(record) {
      return mutate((candidate) => upsertRecord(candidate, record))
    },
    deleteVehicle(vehicleId, deletedAt) {
      return mutate((candidate) => {
        candidate.run('UPDATE vehicles SET deletedAt = ?, updatedAt = ? WHERE id = ?', [deletedAt, deletedAt, vehicleId])
        candidate.run('UPDATE fuel_records SET deletedAt = ?, updatedAt = ? WHERE vehicleId = ? AND deletedAt IS NULL', [
          deletedAt,
          deletedAt,
          vehicleId,
        ])
      })
    },
    exportData() {
      return enqueue(async (): Promise<SyncPayload> => {
        const current = await refresh()
        return {
          version: 1,
          exportedAt: new Date().toISOString(),
          vehicles: getVehicles(current, true),
          records: getRecords(current, true),
        }
      })
    },
    mergeData(remote) {
      return mutate((candidate) => {
        const localVehicles = new Map(getVehicles(candidate, true).map((item) => [item.id, item]))
        const localRecords = new Map(getRecords(candidate, true).map((item) => [item.id, item]))
        for (const item of remote.vehicles) {
          if (shouldAcceptRemote(localVehicles.get(item.id), item)) upsertVehicle(candidate, item)
        }
        for (const item of remote.records) {
          if (shouldAcceptRemote(localRecords.get(item.id), item)) upsertRecord(candidate, item)
        }
      })
    },
    createSafetySnapshot() {
      return enqueue(async () => {
        const current = await refresh()
        const id = await storage.createSnapshot(current.export().slice())
        current.run('PRAGMA foreign_keys = ON')
        return { id, createdAt: new Date().toISOString() }
      })
    },
    restoreSafetySnapshot(id) {
      lastWrite = enqueue(async () => {
        const bytes = await storage.readSnapshot(id)
        const candidate = openDatabase(bytes)
        try {
          assertDatabaseIntegrity(candidate)
          migrateSchema(candidate)
          const current = await storage.read()
          const revision = await storage.write(candidate.export().slice(), current.revision)
          candidate.run('PRAGMA foreign_keys = ON')
          useDatabase(candidate, revision)
        } catch (error) {
          candidate.close()
          throw error
        }
      })
      return lastWrite
    },
    compactTombstones(cutoff) {
      let removed = 0
      return mutate((candidate) => {
        const recordCount =
          rows<{ count: number }>(candidate, 'SELECT COUNT(*) AS count FROM fuel_records WHERE deletedAt IS NOT NULL AND deletedAt < ?', [
            cutoff,
          ])[0]?.count || 0
        const vehicleCount =
          rows<{ count: number }>(candidate, 'SELECT COUNT(*) AS count FROM vehicles WHERE deletedAt IS NOT NULL AND deletedAt < ?', [
            cutoff,
          ])[0]?.count || 0
        candidate.run('DELETE FROM fuel_records WHERE deletedAt IS NOT NULL AND deletedAt < ?', [cutoff])
        candidate.run('DELETE FROM vehicles WHERE deletedAt IS NOT NULL AND deletedAt < ?', [cutoff])
        removed = recordCount + vehicleCount
      }).then(() => removed)
    },
    async getSchemaInfo() {
      const current = await refresh()
      return {
        version: Number(rows<{ user_version: number }>(current, 'PRAGMA user_version')[0]?.user_version || 0),
        backend: 'web-sqlite-wasm' as const,
      }
    },
    conflicts() {
      return enqueue(async () =>
        rows<Record<string, unknown>>(await refresh(), 'SELECT * FROM sync_conflicts ORDER BY detectedAt DESC').map((row) => ({
          id: String(row.id),
          entityType: row.entityType as 'vehicle' | 'record',
          entityId: String(row.entityId),
          localValue: JSON.parse(String(row.localJson)) as Vehicle | FuelRecord,
          remoteValue: JSON.parse(String(row.remoteJson)) as Vehicle | FuelRecord,
          detectedAt: String(row.detectedAt),
        })),
      )
    },
    saveConflicts(conflicts: SyncConflict[]) {
      return mutate((candidate) => {
        for (const conflict of conflicts) {
          candidate.run(
            `INSERT OR IGNORE INTO sync_conflicts (id, entityType, entityId, localJson, remoteJson, detectedAt)
             VALUES (?, ?, ?, ?, ?, ?)`,
            [
              conflict.id,
              conflict.entityType,
              conflict.entityId,
              JSON.stringify(conflict.localValue),
              JSON.stringify(conflict.remoteValue),
              conflict.detectedAt,
            ],
          )
        }
      })
    },
    resolveConflict(id, resolution, merged) {
      return mutate((candidate) => {
        const row = rows<Record<string, unknown>>(candidate, 'SELECT * FROM sync_conflicts WHERE id = ? LIMIT 1', [id])[0]
        if (!row) throw new Error('同步冲突不存在或已处理')
        const selected = merged || (JSON.parse(String(resolution === 'local' ? row.localJson : row.remoteJson)) as Vehicle | FuelRecord)
        const value = { ...selected, updatedAt: new Date().toISOString() }
        if (row.entityType === 'vehicle') upsertVehicle(candidate, value as Vehicle)
        else upsertRecord(candidate, value as FuelRecord)
        candidate.run('DELETE FROM sync_conflicts WHERE id = ?', [id])
      })
    },
    async flush() {
      await lastWrite
    },
    close() {
      return enqueue(async () => {
        db?.close()
        db = undefined
        loadedRevision = -1
        SQL = undefined
        await storage.close()
      })
    },
  }
  return adapter
}

export const webDatabase = createWebDatabase()
