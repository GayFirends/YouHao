const STORAGE_NAME = 'fuel-track-storage'
// Upgrading closes connections held by clients that still write unversioned snapshots.
const STORAGE_VERSION = 2
const STORAGE_STORE = 'databases'
const STORAGE_KEY = 'main'
const REVISION_KEY = 'main-revision'
const SNAPSHOT_PREFIX = 'safety:'

export interface DatabaseSnapshot {
  bytes: Uint8Array | null
  revision: number
}

export class SnapshotConflictError extends Error {
  constructor() {
    super('本地数据已被其他页面更新')
    this.name = 'SnapshotConflictError'
  }
}

function readRevision(value: unknown) {
  if (value === undefined) return 0
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) {
    throw new Error('本地数据库版本标记无效')
  }
  return value
}

function readBytes(value: unknown): Uint8Array | null {
  if (value === undefined) return null
  if (value instanceof Uint8Array) return value
  if (value instanceof ArrayBuffer) return new Uint8Array(value)
  throw new Error('本地数据库文件格式无效')
}

export function createDatabaseStorage() {
  let storagePromise: Promise<IDBDatabase> | undefined

  function openStorage(): Promise<IDBDatabase> {
    if (!('indexedDB' in globalThis)) return Promise.reject(new Error('当前环境不支持 IndexedDB'))
    if (storagePromise) return storagePromise

    storagePromise = new Promise<IDBDatabase>((resolve, reject) => {
      const request = indexedDB.open(STORAGE_NAME, STORAGE_VERSION)
      let blocked = false
      request.onupgradeneeded = () => {
        if (!request.result.objectStoreNames.contains(STORAGE_STORE)) request.result.createObjectStore(STORAGE_STORE)
      }
      request.onsuccess = () => {
        const storage = request.result
        if (blocked) {
          storage.close()
          return
        }
        storage.onversionchange = () => {
          storage.close()
          storagePromise = undefined
        }
        resolve(storage)
      }
      request.onerror = () => reject(request.error || new Error('无法打开本地数据库存储'))
      request.onblocked = () => {
        blocked = true
        reject(new Error('数据库升级被其他页面阻止，请关闭其他油迹页面后重试'))
      }
    }).catch((error) => {
      storagePromise = undefined
      throw error
    })
    return storagePromise
  }

  return {
    async read(): Promise<DatabaseSnapshot> {
      const storage = await openStorage()
      return new Promise((resolve, reject) => {
        const transaction = storage.transaction(STORAGE_STORE, 'readonly')
        const store = transaction.objectStore(STORAGE_STORE)
        const bytes = store.get(STORAGE_KEY)
        const revision = store.get(REVISION_KEY)
        transaction.oncomplete = () => {
          try {
            resolve({ bytes: readBytes(bytes.result), revision: readRevision(revision.result) })
          } catch (error) {
            reject(error)
          }
        }
        transaction.onerror = () => reject(transaction.error || new Error('读取本地数据库失败'))
        transaction.onabort = () => reject(transaction.error || new Error('读取本地数据库已中止'))
      })
    },

    async write(bytes: Uint8Array, expectedRevision: number): Promise<number> {
      const storage = await openStorage()
      return new Promise((resolve, reject) => {
        const transaction = storage.transaction(STORAGE_STORE, 'readwrite', { durability: 'strict' })
        const store = transaction.objectStore(STORAGE_STORE)
        const revision = store.get(REVISION_KEY)
        let failure: unknown
        revision.onsuccess = () => {
          try {
            if (readRevision(revision.result) !== expectedRevision) throw new SnapshotConflictError()
            if (!Number.isSafeInteger(expectedRevision + 1)) throw new Error('本地数据库版本标记超出范围')
            store.put(bytes, STORAGE_KEY)
            store.put(expectedRevision + 1, REVISION_KEY)
          } catch (error) {
            failure = error
            transaction.abort()
          }
        }
        transaction.oncomplete = () => resolve(expectedRevision + 1)
        transaction.onerror = () => reject(failure || transaction.error || new Error('保存本地数据库失败'))
        transaction.onabort = () => reject(failure || transaction.error || new Error('保存本地数据库已中止'))
      })
    },

    async createSnapshot(bytes: Uint8Array, id: string = `${Date.now()}-${crypto.randomUUID()}`) {
      const storage = await openStorage()
      await new Promise<void>((resolve, reject) => {
        const transaction = storage.transaction(STORAGE_STORE, 'readwrite', { durability: 'strict' })
        transaction.objectStore(STORAGE_STORE).put(bytes.slice(), SNAPSHOT_PREFIX + id)
        transaction.oncomplete = () => resolve()
        transaction.onerror = () => reject(transaction.error || new Error('创建安全快照失败'))
        transaction.onabort = () => reject(transaction.error || new Error('创建安全快照已中止'))
      })
      return id
    },

    async readSnapshot(id: string) {
      const storage = await openStorage()
      return new Promise<Uint8Array>((resolve, reject) => {
        const transaction = storage.transaction(STORAGE_STORE, 'readonly')
        const request = transaction.objectStore(STORAGE_STORE).get(SNAPSHOT_PREFIX + id)
        transaction.oncomplete = () => {
          try {
            const bytes = readBytes(request.result)
            if (!bytes) throw new Error('安全快照不存在')
            resolve(bytes)
          } catch (error) {
            reject(error)
          }
        }
        transaction.onerror = () => reject(transaction.error || new Error('读取安全快照失败'))
      })
    },

    async listSnapshots() {
      const storage = await openStorage()
      return new Promise<string[]>((resolve, reject) => {
        const transaction = storage.transaction(STORAGE_STORE, 'readonly')
        const request = transaction.objectStore(STORAGE_STORE).getAllKeys()
        transaction.oncomplete = () =>
          resolve(
            request.result
              .map(String)
              .filter((key) => key.startsWith(SNAPSHOT_PREFIX))
              .map((key) => key.slice(SNAPSHOT_PREFIX.length)),
          )
        transaction.onerror = () => reject(transaction.error || new Error('读取安全快照列表失败'))
      })
    },

    async close() {
      const storage = await storagePromise
      storage?.close()
      storagePromise = undefined
    },
  }
}
