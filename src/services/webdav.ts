import { database } from './database'
import type { SyncPayload, WebDavConfig } from '../types'

const MAX_SYNC_ATTEMPTS = 3

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

async function request(config: WebDavConfig, url: string, method: string, body?: string, conditionalHeaders: Record<string, string> = {}) {
  return fetch(url, {
    method,
    headers: {
      Authorization: authHeader(config),
      ...conditionalHeaders,
      ...(body ? { 'Content-Type': 'application/json; charset=utf-8' } : {}),
    },
    body,
  })
}

function validatePayload(value: unknown): SyncPayload {
  const payload = value as Partial<SyncPayload>
  if (payload.version !== 1 || !Array.isArray(payload.vehicles) || !Array.isArray(payload.records)) {
    throw new Error('云端数据格式不受支持')
  }
  return payload as SyncPayload
}

export async function testWebDav(config: WebDavConfig) {
  if (!config.url) throw new Error('请填写 WebDAV 地址')
  const response = await request(config, directoryUrl(config), 'PROPFIND', undefined, { Depth: '0' })
  if (response.status === 401 || response.status === 403) throw new Error('账号或应用密码无效')
  if (response.status === 404) throw new Error('WebDAV 同步目录不存在')
  if (!response.ok && response.status !== 207) throw new Error(`连接失败（HTTP ${response.status}）`)
}

export async function syncWebDav(config: WebDavConfig) {
  if (!config.url) throw new Error('请先配置 WebDAV')

  for (let attempt = 1; attempt <= MAX_SYNC_ATTEMPTS; attempt += 1) {
    const download = await request(config, fileUrl(config), 'GET')
    let etag: string | null = null
    let lastModified: string | null = null

    if (download.ok) {
      etag = download.headers.get('ETag')
      lastModified = download.headers.get('Last-Modified')
      await database.mergeData(validatePayload(await download.json()))
    } else if (download.status !== 404) {
      if (download.status === 401 || download.status === 403) throw new Error('WebDAV 认证失败')
      throw new Error(`下载失败（HTTP ${download.status}）`)
    }

    const conditionalHeaders: Record<string, string> = {}
    if (etag) conditionalHeaders['If-Match'] = etag
    else if (lastModified) conditionalHeaders['If-Unmodified-Since'] = lastModified
    else if (download.status === 404) conditionalHeaders['If-None-Match'] = '*'

    const payload = JSON.stringify(await database.exportData(), null, 2)
    const upload = await request(config, fileUrl(config), 'PUT', payload, conditionalHeaders)
    if (upload.ok) return new Date()

    if (upload.status === 412 && attempt < MAX_SYNC_ATTEMPTS) continue
    if (upload.status === 409) throw new Error('WebDAV 同步目录不存在，请先在服务器上创建该目录')
    if (upload.status === 401 || upload.status === 403) throw new Error('WebDAV 没有写入权限')
    if (upload.status === 412) throw new Error('云端数据持续发生冲突，请稍后重试')
    throw new Error(`上传失败（HTTP ${upload.status}）`)
  }

  throw new Error('同步重试次数已用尽')
}
