import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { IDBFactory, IDBObjectStore as FakeObjectStore } from 'fake-indexeddb'
import initSqlJs, { type SqlJsStatic } from 'sql.js'
import { createWebDatabase } from '../database-web'
import { createDatabaseStorage, SnapshotConflictError } from '../database-storage'
import { validateSyncPayload } from '../sync-validation'
import type { FuelRecord, Vehicle } from '../../types'

const stamp = '2026-09-15T00:00:00.000Z'
const require = createRequire(import.meta.url)
let SQL: SqlJsStatic
let clients: ReturnType<typeof createWebDatabase>[]
let localValues: Map<string, string>

beforeAll(async () => {
  SQL = await initSqlJs({ wasmBinary: readFileSync(require.resolve('sql.js/dist/sql-wasm.wasm')) })
})

beforeEach(() => {
  clients = []
  localValues = new Map()
  vi.stubGlobal('indexedDB', new IDBFactory())
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => localValues.get(key) ?? null,
    setItem: (key: string, value: string) => {
      localValues.set(key, value)
    },
    removeItem: (key: string) => {
      localValues.delete(key)
    },
  })
})

afterEach(async () => {
  await Promise.all(clients.map((client) => client.close()))
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

function client() {
  const instance = createWebDatabase(async () => SQL)
  clients.push(instance)
  return instance
}

function record(vehicleId: string, overrides: Partial<FuelRecord> = {}): FuelRecord {
  return {
    id: crypto.randomUUID(),
    vehicleId,
    date: '2026-09-15',
    odometer: 1000,
    liters: 40,
    amount: 280,
    pumpAmount: 320,
    pricePerLiter: 7,
    isFull: true,
    station: '',
    note: '',
    createdAt: stamp,
    updatedAt: stamp,
    deletedAt: null,
    ...overrides,
  }
}

function failNextWrite() {
  vi.spyOn(FakeObjectStore.prototype, 'put').mockImplementationOnce(() => {
    throw new DOMException('模拟存储空间不足', 'QuotaExceededError')
  })
}

function legacyBytes(version: 1 | 2) {
  const fixture = new SQL.Database()
  fixture.run(`
    CREATE TABLE vehicles (
      id TEXT PRIMARY KEY, name TEXT NOT NULL, plate TEXT NOT NULL, fuelType TEXT NOT NULL,
      initialOdometer REAL NOT NULL, createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL, deletedAt TEXT
    );
    CREATE TABLE fuel_records (
      id TEXT PRIMARY KEY, vehicleId TEXT NOT NULL, date TEXT NOT NULL, odometer REAL NOT NULL,
      liters REAL NOT NULL, amount REAL NOT NULL, pricePerLiter REAL NOT NULL, isFull INTEGER NOT NULL,
      station TEXT NOT NULL, note TEXT NOT NULL, createdAt TEXT NOT NULL, updatedAt TEXT NOT NULL,
      deletedAt TEXT${version === 2 ? ', FOREIGN KEY (vehicleId) REFERENCES vehicles(id)' : ''}
    );
    PRAGMA user_version = ${version};
  `)
  fixture.run('INSERT INTO vehicles VALUES (?, ?, ?, ?, ?, ?, ?, ?)', ['old-car', '旧车辆', '', '92#', 100, stamp, stamp, null])
  fixture.run('INSERT INTO fuel_records VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)', [
    'old-record',
    'old-car',
    '2026-09-15',
    500,
    40,
    280,
    7,
    1,
    '旧加油站',
    '旧备注',
    stamp,
    stamp,
    null,
  ])
  const bytes = fixture.export()
  fixture.close()
  return bytes
}

async function seedOldIndexedDb(bytes: Uint8Array) {
  await new Promise<void>((resolve, reject) => {
    const request = indexedDB.open('fuel-track-storage', 1)
    request.onupgradeneeded = () => request.result.createObjectStore('databases')
    request.onerror = () => reject(request.error)
    request.onsuccess = () => {
      const storage = request.result
      const transaction = storage.transaction('databases', 'readwrite')
      transaction.objectStore('databases').put(bytes, 'main')
      transaction.oncomplete = () => {
        storage.close()
        resolve()
      }
      transaction.onabort = () => {
        storage.close()
        reject(transaction.error)
      }
    }
  })
}

describe('web database persistence', () => {
  it('supports safety snapshot restore and reports schema information', async () => {
    const database = client()
    await database.init()
    const original = record((await database.vehicles())[0].id)
    await database.saveRecord(original)
    const snapshot = await database.createSafetySnapshot()
    await database.saveRecord({ ...original, note: 'changed', updatedAt: '2026-09-15T01:00:00.000Z' })
    await database.restoreSafetySnapshot(snapshot.id)
    expect(await database.records()).toEqual([original])
    expect(await database.getSchemaInfo()).toEqual({ version: 5, backend: 'web-sqlite-wasm' })
  })

  it('paginates records and compacts old tombstones', async () => {
    const database = client()
    await database.init()
    const vehicleId = (await database.vehicles())[0].id
    await database.saveRecord(record(vehicleId, { id: 'old', deletedAt: '2026-01-01T00:00:00.000Z' }))
    await database.saveRecord(record(vehicleId, { id: 'active', odometer: 2000 }))
    expect((await database.listRecords({ vehicleId, limit: 1 })).items[0].id).toBe('active')
    expect((await database.getVehicleSummary(vehicleId)).recordCount).toBe(1)
    expect(await database.compactTombstones('2026-04-01T00:00:00.000Z')).toBe(1)
    expect((await database.exportData()).records.map((item) => item.id)).toEqual(['active'])
  })

  it('keeps concurrent first launches on the same default vehicle', async () => {
    const first = client()
    const second = client()
    await Promise.all([first.init(), second.init()])
    expect(await first.vehicles()).toHaveLength(1)
    expect(await second.vehicles()).toEqual(await first.vehicles())
  })

  it('preserves both records when two pages save from the same snapshot', async () => {
    const first = client()
    const second = client()
    await first.init()
    await second.init()
    const vehicle = (await first.vehicles())[0]
    const one = record(vehicle.id)
    const two = record(vehicle.id)
    await Promise.all([first.saveRecord(one), second.saveRecord(two)])

    const reopened = client()
    await reopened.init()
    expect(new Set((await reopened.records()).map((item) => item.id))).toEqual(new Set([one.id, two.id]))
    expect(await first.records()).toEqual(await reopened.records())
    expect(await second.records()).toEqual(await reopened.records())
  })

  it('does not let a failed save leak into reads, exports, or a later successful save', async () => {
    const database = client()
    await database.init()
    const vehicle = (await database.vehicles())[0]
    const failed = record(vehicle.id)
    failNextWrite()
    await expect(database.saveRecord(failed)).rejects.toThrow('模拟存储空间不足')
    await expect(database.flush()).rejects.toThrow('模拟存储空间不足')
    expect(await database.records()).toEqual([])
    expect((await database.exportData()).records).toEqual([])

    const retried = record(vehicle.id)
    await database.saveRecord(retried)
    await database.flush()
    const reopened = client()
    await reopened.init()
    expect(await reopened.records()).toEqual([retried])
  })

  it('leaves the existing record intact when an edit cannot be persisted', async () => {
    const database = client()
    await database.init()
    const original = record((await database.vehicles())[0].id)
    await database.saveRecord(original)
    failNextWrite()
    await expect(database.saveRecord({ ...original, note: '未保存的编辑' })).rejects.toThrow()
    expect(await database.records()).toEqual([original])
  })

  it('continues queued operations after a failed write', async () => {
    const database = client()
    await database.init()
    const vehicleId = (await database.vehicles())[0].id
    const one = record(vehicleId)
    const two = record(vehicleId)
    failNextWrite()
    const rejected = expect(database.saveRecord(one)).rejects.toThrow('模拟存储空间不足')
    const successful = database.saveRecord(two)
    await Promise.all([rejected, successful])
    expect(await database.records()).toEqual([two])
  })

  it('does not duplicate a record when an acknowledged operation is retried with the same id', async () => {
    const database = client()
    await database.init()
    const saved = record((await database.vehicles())[0].id)
    await database.saveRecord(saved)
    await database.saveRecord(saved)
    expect(await database.records()).toEqual([saved])
  })

  it('keeps vehicle and record deletion atomic when persistence fails', async () => {
    const database = client()
    await database.init()
    const vehicle = (await database.vehicles())[0]
    const saved = record(vehicle.id)
    await database.saveRecord(saved)
    failNextWrite()
    await expect(database.deleteVehicle(vehicle.id, stamp)).rejects.toThrow()
    expect(await database.vehicles()).toEqual([vehicle])
    expect(await database.records()).toEqual([saved])

    await database.deleteVehicle(vehicle.id, stamp)
    expect(await database.vehicles()).toEqual([])
    expect(await database.records()).toEqual([])
    expect((await database.exportData()).records[0].deletedAt).toBe(stamp)
  })

  it('enforces foreign keys after initialization, export and previous saves', async () => {
    const database = client()
    await database.init()
    await expect(database.saveRecord(record('missing-car'))).rejects.toThrow(/FOREIGN KEY/)
    const saved = record((await database.vehicles())[0].id)
    await database.saveRecord(saved)
    await database.exportData()
    await expect(database.saveRecord(record('still-missing'))).rejects.toThrow(/FOREIGN KEY/)
    expect(await database.records()).toEqual([saved])
  })

  it('rolls back the complete merge when one record violates a constraint', async () => {
    const database = client()
    await database.init()
    const existing = (await database.vehicles())[0]
    const remoteVehicle: Vehicle = { ...existing, id: 'remote-car' }
    await expect(
      database.mergeData({
        version: 1,
        exportedAt: stamp,
        vehicles: [remoteVehicle],
        records: [record(remoteVehicle.id), record(remoteVehicle.id, { liters: -1 })],
      }),
    ).rejects.toThrow(/CHECK/)
    expect(await database.vehicles()).toEqual([existing])
    expect(await database.records()).toEqual([])
  })

  it('converges when backups and database rows have different field order at the same timestamp', async () => {
    const database = client()
    await database.init()
    const vehicle = (await database.vehicles())[0]
    const saved = record(vehicle.id, { note: 'z' })
    await database.saveRecord(saved)
    const remote = validateSyncPayload({
      version: 1,
      exportedAt: stamp,
      vehicles: [vehicle],
      records: [{ ...saved, note: 'a' }],
    })
    await database.mergeData(remote)
    expect((await database.records())[0].note).toBe('z')
    await database.mergeData(await database.exportData())
    expect(await database.records()).toEqual([saved])
  })

  it('persists the complete winning record, including creation time', async () => {
    const database = client()
    await database.init()
    const vehicle = (await database.vehicles())[0]
    const original = record(vehicle.id)
    await database.saveRecord(original)
    const winning = { ...original, createdAt: '2026-09-14T00:00:00.000Z', updatedAt: '2026-09-15T00:01:00.000Z', note: 'newer' }
    await database.mergeData({ version: 1, exportedAt: stamp, vehicles: [vehicle], records: [winning] })
    expect(await database.records()).toEqual([winning])
  })

  it('persists conflicts and resolves local, remote, and manually merged versions atomically', async () => {
    const database = client()
    await database.init()
    const vehicleId = (await database.vehicles())[0].id
    const local = record(vehicleId, { id: 'conflicted', note: 'local' })
    const remote = { ...local, note: 'remote' }

    await database.saveConflicts([
      {
        id: 'record:conflicted',
        entityType: 'record',
        entityId: 'conflicted',
        localValue: local,
        remoteValue: remote,
        detectedAt: stamp,
      },
    ])
    expect(await database.conflicts()).toMatchObject([
      { id: 'record:conflicted', localValue: { note: 'local' }, remoteValue: { note: 'remote' } },
    ])
    await database.resolveConflict('record:conflicted', 'remote')
    expect((await database.getRecord('conflicted'))?.note).toBe('remote')
    expect(await database.conflicts()).toEqual([])

    for (const [id, resolution, merged, expected] of [
      ['local-choice', 'local', undefined, 'local'],
      ['manual-choice', 'local', { ...local, id: 'manual-choice', note: 'merged' }, 'merged'],
    ] as const) {
      const localValue = { ...local, id }
      const remoteValue = { ...remote, id }
      await database.saveConflicts([
        {
          id: `record:${id}`,
          entityType: 'record',
          entityId: id,
          localValue,
          remoteValue,
          detectedAt: stamp,
        },
      ])
      await database.resolveConflict(`record:${id}`, resolution, merged)
      expect((await database.getRecord(id))?.note).toBe(expected)
    }
    expect(await database.conflicts()).toEqual([])
  })
})

describe('web storage migrations', () => {
  it.each([1, 2] as const)('upgrades a schema v%i database from the old unversioned IndexedDB store', async (version) => {
    await seedOldIndexedDb(legacyBytes(version))
    const database = client()
    await database.init()
    expect(await database.vehicles()).toHaveLength(1)
    expect((await database.records())[0]).toMatchObject({
      id: 'old-record',
      amount: 280,
      pumpAmount: 280,
      station: '旧加油站',
      note: '旧备注',
    })
    const reopened = client()
    await reopened.init()
    expect(await reopened.exportData()).toMatchObject({ vehicles: await database.vehicles(), records: await database.records() })
  })

  it('retains the localStorage backup until the migration is durably committed', async () => {
    localValues.set('fuel-track-sqlite-v1', Buffer.from(legacyBytes(1)).toString('base64'))
    const database = client()
    failNextWrite()
    await expect(database.init()).rejects.toThrow('模拟存储空间不足')
    expect(localValues.has('fuel-track-sqlite-v1')).toBe(true)
    await database.init()
    expect(localValues.has('fuel-track-sqlite-v1')).toBe(false)
    expect((await database.records())[0].pumpAmount).toBe(280)
  })

  it('does not overwrite a database created by a newer app', async () => {
    const fixture = new SQL.Database(legacyBytes(2))
    fixture.run('PRAGMA user_version = 6')
    const bytes = fixture.export()
    fixture.close()
    await seedOldIndexedDb(bytes)
    await expect(client().init()).rejects.toThrow('更高版本')
    const storage = createDatabaseStorage()
    try {
      expect((await storage.read()).bytes).toEqual(bytes)
    } finally {
      await storage.close()
    }
  })
})

describe('snapshot compare-and-swap', () => {
  it('rejects stale revisions without replacing the latest snapshot', async () => {
    const storage = createDatabaseStorage()
    try {
      await storage.write(new Uint8Array([1]), 0)
      await expect(storage.write(new Uint8Array([2]), 0)).rejects.toBeInstanceOf(SnapshotConflictError)
      expect(await storage.read()).toEqual({ bytes: new Uint8Array([1]), revision: 1 })
    } finally {
      await storage.close()
    }
  })

  it('rolls back both bytes and revision if writing the revision fails', async () => {
    const storage = createDatabaseStorage()
    try {
      await storage.write(new Uint8Array([1]), 0)
      const put = FakeObjectStore.prototype.put
      vi.spyOn(FakeObjectStore.prototype, 'put')
        .mockImplementationOnce(function (this: IDBObjectStore, value, key) {
          return put.call(this, value, key)
        })
        .mockImplementationOnce(() => {
          throw new Error('版本写入失败')
        })
      await expect(storage.write(new Uint8Array([2]), 1)).rejects.toThrow('版本写入失败')
      expect(await storage.read()).toEqual({ bytes: new Uint8Array([1]), revision: 1 })
    } finally {
      await storage.close()
    }
  })
})
