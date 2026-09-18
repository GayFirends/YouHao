import { database } from './database'
import type { SyncMetadata, SyncPayload, WebDavConfig } from '../types'
import { decryptSyncDocument, encryptSyncPayload } from './sync-crypto'
import { AppError } from './app-error'
import { getDeviceId } from './device-identity'
import { detectSyncConflicts, saveSyncBase, withoutConflicts } from './sync-conflicts'

const MAX_SYNC_ATTEMPTS = 3
const REQUEST_TIMEOUT_MS = 30_000
const MAX_CLOCK_SKEW_MS = 5 * 60_000
const TOMBSTONE_RETENTION_MS = 90 * 24 * 60 * 60_000
const METADATA_CACHE_KEY = 'fuel-track-sync-metadata-v1'

function syncTarget(config: WebDavConfig) {
  return `${config.url.replace(/\/+$/, '')}/${config.fileName || 'fuel-track.json'}`
}

function metadataCacheKey(config?: WebDavConfig) {
  return `${METADATA_CACHE_KEY}:${encodeURIComponent(config ? syncTarget(config) : 'default')}`
}

function validateConfig(config: WebDavConfig) {
  if (!config.url) throw new Error('请填写 WebDAV 地址')
  let url: URL
  try {
    url = new URL(config.url)
  } catch {
    throw new Error('WebDAV 地址格式无效')
  }
  if (url.protocol !== 'https:' && !(url.protocol === 'http:' && ['localhost', '127.0.0.1'].includes(url.hostname))) {
    throw new Error('WebDAV 必须使用 HTTPS（本机调试除外）')
  }
  if (config.encryptionEnabled && (config.encryptionPassphrase?.length || 0) < 8) {
    throw new AppError('SYNC_ENCRYPTION_FAILED', '启用加密时，同步口令至少需要 8 个字符')
  }
}

function assertClockSane(response: Response) {
  const serverDate = response.headers.get('Date')
  if (!serverDate) return
  const serverTime = Date.parse(serverDate)
  if (Number.isFinite(serverTime) && Math.abs(Date.now() - serverTime) > MAX_CLOCK_SKEW_MS) {
    throw new Error('设备时间与 WebDAV 服务器相差超过 5 分钟，请校准系统时间后同步')
  }
}

function assertPayloadClock(payload: SyncPayload) {
  const futureLimit = Date.now() + MAX_CLOCK_SKEW_MS
  if (Date.parse(payload.exportedAt) > futureLimit) throw new Error('同步数据包含未来时间，请先校准产生该数据的设备时间')
  const futureItem = [...payload.vehicles, ...payload.records].find((item) => Date.parse(item.updatedAt) > futureLimit)
  if (futureItem) throw new Error('同步数据包含未来时间，请先校准产生该数据的设备时间')
}

async function parseRemoteResponse(response: Response, passphrase = '') {
  const declaredSize = Number(response.headers.get('Content-Length') || 0)
  if (declaredSize > 20 * 1024 * 1024) throw new Error('云端备份超过 20 MB，拒绝同步')
  const text = await response.text()
  if (text.length > 20 * 1024 * 1024) throw new Error('云端备份超过 20 MB，拒绝同步')
  let value: unknown
  try {
    value = JSON.parse(text)
  } catch {
    throw new Error('云端数据不是有效的 JSON')
  }
  try {
    return await decryptSyncDocument(value, passphrase)
  } catch (error) {
    if (error instanceof AppError) throw error
    throw new AppError('SYNC_FORMAT_INVALID', '云端数据格式不受支持', { cause: error })
  }
}

function authHeader(config: WebDavConfig) {
  const bytes = new TextEncoder().encode(`${config.username}:${config.password}`)
  let binary = ''
  bytes.forEach((byte) => (binary += String.fromCharCode(byte)))
  return `Basic ${btoa(binary)}`
}

function directoryUrl(config: WebDavConfig) {
  return `${config.url.replace(/\/+$/, '')}/`
}

function fileUrl(config: WebDavConfig) {
  return `${directoryUrl(config)}${encodeURIComponent(config.fileName || 'fuel-track.json')}`
}

function metadataUrl(config: WebDavConfig) {
  return `${directoryUrl(config)}${encodeURIComponent(`${config.fileName || 'fuel-track.json'}.meta.json`)}`
}

async function request(config: WebDavConfig, url: string, method: string, body?: string, conditionalHeaders: Record<string, string> = {}) {
  const controller = new AbortController()
  const timer = globalThis.setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS)
  try {
    return await fetch(url, {
      method,
      headers: {
        Authorization: authHeader(config),
        ...conditionalHeaders,
        ...(body ? { 'Content-Type': 'application/json; charset=utf-8' } : {}),
      },
      body,
      signal: controller.signal,
    })
  } catch (error) {
    if (error && typeof error === 'object' && 'name' in error && error.name === 'AbortError')
      throw new AppError('SYNC_TIMEOUT', 'WebDAV 请求超时')
    throw new AppError('NETWORK_FAILED', error instanceof Error ? error.message : 'WebDAV 网络请求失败', { cause: error })
  } finally {
    globalThis.clearTimeout(timer)
  }
}

export async function testWebDav(config: WebDavConfig) {
  validateConfig(config)
  const response = await request(config, directoryUrl(config), 'PROPFIND', undefined, { Depth: '0' })
  assertClockSane(response)
  if (response.status === 401 || response.status === 403) throw new Error('账号或应用密码无效')
  if (response.status === 404) throw new Error('WebDAV 同步目录不存在')
  if (!response.ok && response.status !== 207) throw new Error(`连接失败（HTTP ${response.status}）`)
}

export function getCachedSyncMetadata(config?: WebDavConfig): SyncMetadata {
  try {
    const value = JSON.parse(localStorage.getItem(metadataCacheKey(config)) || '') as SyncMetadata
    if (value.version === 1 && value.devices && typeof value.devices === 'object') return value
  } catch {
    // A missing or malformed cache is replaced after the next successful sync.
  }
  return { version: 1, devices: {} }
}

async function syncDeviceMetadata(config: WebDavConfig, acknowledgedThrough: string) {
  for (let attempt = 1; attempt <= MAX_SYNC_ATTEMPTS; attempt += 1) {
    const download = await request(config, metadataUrl(config), 'GET')
    let metadata: SyncMetadata = { version: 1, devices: {} }
    const headers: Record<string, string> = {}
    if (download.ok) {
      const value = JSON.parse(await download.text()) as SyncMetadata
      if (value.version !== 1 || !value.devices || typeof value.devices !== 'object') throw new Error('设备同步元数据格式无效')
      metadata = value
      const etag = download.headers.get('ETag')
      if (etag) headers['If-Match'] = etag
      else {
        const lastModified = download.headers.get('Last-Modified')
        if (lastModified) headers['If-Unmodified-Since'] = lastModified
        else throw new Error('服务器未提供设备元数据的 ETag 或 Last-Modified，已跳过安全清理')
      }
    } else if (download.status === 404) headers['If-None-Match'] = '*'
    else throw new Error(`设备同步元数据下载失败（HTTP ${download.status}）`)

    const deviceId = getDeviceId()
    metadata.devices[deviceId] = { deviceId, lastSeenAt: acknowledgedThrough, acknowledgedThrough }
    const upload = await request(config, metadataUrl(config), 'PUT', JSON.stringify(metadata), headers)
    if (upload.ok) {
      localStorage.setItem(metadataCacheKey(config), JSON.stringify(metadata))
      const acknowledgements = Object.values(metadata.devices)
        .map((device) => Date.parse(device.acknowledgedThrough))
        .filter(Number.isFinite)
      if (acknowledgements.length === Object.keys(metadata.devices).length && acknowledgements.length) {
        const retentionCutoff = Date.now() - TOMBSTONE_RETENTION_MS
        const safeCutoff = new Date(Math.min(retentionCutoff, ...acknowledgements)).toISOString()
        await database.compactTombstones(safeCutoff)
      }
      return metadata
    }
    if (upload.status !== 412 || attempt === MAX_SYNC_ATTEMPTS) throw new Error(`设备同步元数据上传失败（HTTP ${upload.status}）`)
  }
  throw new Error('设备同步元数据重试次数已用尽')
}

export async function syncWebDav(config: WebDavConfig) {
  validateConfig(config)
  const target = syncTarget(config)

  for (let attempt = 1; attempt <= MAX_SYNC_ATTEMPTS; attempt += 1) {
    const download = await request(config, fileUrl(config), 'GET')
    assertClockSane(download)
    let etag: string | null = null
    let lastModified: string | null = null

    if (download.ok) {
      etag = download.headers.get('ETag')
      lastModified = download.headers.get('Last-Modified')
      const remotePayload = await parseRemoteResponse(download, config.encryptionPassphrase)
      assertPayloadClock(remotePayload)
      const localBeforeMerge = await database.exportData()
      const conflicts = await detectSyncConflicts(localBeforeMerge, remotePayload, target)
      if (conflicts.length) await database.saveConflicts(conflicts)
      await database.mergeData(withoutConflicts(remotePayload, conflicts))
    } else if (download.status !== 404) {
      if (download.status === 401 || download.status === 403) throw new Error('WebDAV 认证失败')
      throw new Error(`下载失败（HTTP ${download.status}）`)
    }

    const conditionalHeaders: Record<string, string> = {}
    if (etag) conditionalHeaders['If-Match'] = etag
    else if (lastModified) conditionalHeaders['If-Unmodified-Since'] = lastModified
    else if (download.status === 404) conditionalHeaders['If-None-Match'] = '*'

    const localPayload = await database.exportData()
    assertPayloadClock(localPayload)
    const document = config.encryptionEnabled ? await encryptSyncPayload(localPayload, config.encryptionPassphrase || '') : localPayload
    const payload = JSON.stringify(document)
    const upload = await request(config, fileUrl(config), 'PUT', payload, conditionalHeaders)
    if (upload.ok) {
      const completedAt = new Date()
      await saveSyncBase(await database.exportData(), target)
      try {
        await syncDeviceMetadata(config, completedAt.toISOString())
      } catch (error) {
        console.warn('Record sync completed but device acknowledgement failed', error)
      }
      return completedAt
    }

    if (upload.status === 412 && attempt < MAX_SYNC_ATTEMPTS) continue
    if (upload.status === 409) throw new Error('WebDAV 同步目录不存在，请先在服务器上创建该目录')
    if (upload.status === 401 || upload.status === 403) throw new Error('WebDAV 没有写入权限')
    if (upload.status === 412) throw new Error('云端数据持续发生冲突，请稍后重试')
    throw new Error(`上传失败（HTTP ${upload.status}）`)
  }

  throw new Error('同步重试次数已用尽')
}
