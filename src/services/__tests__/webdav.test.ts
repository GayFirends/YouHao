import { beforeEach, describe, expect, it, vi } from 'vitest'

const { database } = vi.hoisted(() => ({
  database: {
    mergeData: vi.fn(async () => undefined),
    exportData: vi.fn(async () => ({ version: 1 as const, exportedAt: '', vehicles: [], records: [] })),
    saveConflicts: vi.fn(async () => undefined),
    compactTombstones: vi.fn(async () => 0),
  },
}))

vi.mock('../database', () => ({ database }))

import { getCachedSyncMetadata, syncWebDav, testWebDav } from '../webdav'

const config = { url: 'https://dav.example.com/fuel', username: 'user', password: 'pass', fileName: 'data.json' }
const payload = JSON.stringify({ version: 1, exportedAt: '2026-01-01T00:00:00.000Z', vehicles: [], records: [] })
let localValues: Map<string, string>

describe('WebDAV synchronization', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localValues = new Map<string, string>([['fuel-track-device-id', 'this-device']])
    vi.stubGlobal('localStorage', {
      getItem: (key: string) => localValues.get(key) ?? null,
      setItem: (key: string, value: string) => localValues.set(key, value),
      removeItem: (key: string) => localValues.delete(key),
      clear: () => localValues.clear(),
    })
  })

  it('tests the configured directory instead of the data file', async () => {
    const fetchMock = vi.fn(async () => new Response('', { status: 207 }))
    vi.stubGlobal('fetch', fetchMock)
    await testWebDav(config)
    expect(fetchMock).toHaveBeenCalledWith('https://dav.example.com/fuel/', expect.objectContaining({ method: 'PROPFIND' }))
  })

  it.each([
    [{ ...config, url: '' }, '请填写'],
    [{ ...config, url: 'not a url' }, '格式无效'],
    [{ ...config, url: 'http://dav.example.com' }, '必须使用 HTTPS'],
    [{ ...config, encryptionEnabled: true, encryptionPassphrase: 'short' }, '至少需要 8'],
  ])('rejects unsafe configuration', async (candidate, message) => {
    await expect(testWebDav(candidate)).rejects.toThrow(message)
  })

  it.each([
    [401, '账号或应用密码无效'],
    [403, '账号或应用密码无效'],
    [404, '同步目录不存在'],
    [500, '连接失败'],
  ])('maps directory status %i to an actionable error', async (status, message) => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('', { status })),
    )
    await expect(testWebDav(config)).rejects.toThrow(message)
  })

  it('rejects a server clock that is too far from the device', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('', { status: 207, headers: { Date: '2020-01-01T00:00:00Z' } })),
    )
    await expect(testWebDav(config)).rejects.toThrow('相差超过 5 分钟')
  })

  it('downloads and retries when an ETag conflict occurs', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(new Response(payload, { status: 200, headers: { ETag: '"v1"' } }))
      .mockResolvedValueOnce(new Response('', { status: 412 }))
      .mockResolvedValueOnce(new Response(payload, { status: 200, headers: { ETag: '"v2"' } }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(new Response('', { status: 404 }))
      .mockResolvedValueOnce(new Response(null, { status: 201 }))
    vi.stubGlobal('fetch', fetchMock)

    await syncWebDav(config)

    expect(fetchMock).toHaveBeenCalledTimes(6)
    expect(fetchMock.mock.calls[1][1].headers['If-Match']).toBe('"v1"')
    expect(fetchMock.mock.calls[3][1].headers['If-Match']).toBe('"v2"')
    expect(database.mergeData).toHaveBeenCalledTimes(2)
  })

  it('creates a missing file conditionally', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(new Response('', { status: 404 }))
      .mockResolvedValueOnce(new Response(null, { status: 201 }))
      .mockResolvedValueOnce(new Response('', { status: 404 }))
      .mockResolvedValueOnce(new Response(null, { status: 201 }))
    vi.stubGlobal('fetch', fetchMock)
    await syncWebDav(config)
    expect(fetchMock.mock.calls[1][1].headers['If-None-Match']).toBe('*')
  })

  it('falls back to Last-Modified when no ETag is available', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(new Response(payload, { status: 200, headers: { 'Last-Modified': 'Wed, 16 Sep 2026 00:00:00 GMT' } }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(new Response('', { status: 404 }))
      .mockResolvedValueOnce(new Response(null, { status: 201 }))
    vi.stubGlobal('fetch', fetchMock)
    await syncWebDav(config)
    expect(fetchMock.mock.calls[1][1].headers['If-Unmodified-Since']).toContain('16 Sep 2026')
  })

  it('creates target-scoped device metadata conditionally and compacts only through the safe acknowledgement', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(new Response('', { status: 404 }))
      .mockResolvedValueOnce(new Response(null, { status: 201 }))
      .mockResolvedValueOnce(new Response('', { status: 404 }))
      .mockResolvedValueOnce(new Response(null, { status: 201 }))
    vi.stubGlobal('fetch', fetchMock)
    await syncWebDav(config)

    expect(fetchMock.mock.calls[2][0]).toContain('data.json.meta.json')
    expect(fetchMock.mock.calls[3][1].headers['If-None-Match']).toBe('*')
    expect(JSON.parse(String(fetchMock.mock.calls[3][1].body)).devices['this-device']).toMatchObject({ deviceId: 'this-device' })
    expect(database.compactTombstones).toHaveBeenCalledOnce()
    expect(Object.keys(getCachedSyncMetadata(config).devices)).toEqual(['this-device'])
    expect(getCachedSyncMetadata({ ...config, fileName: 'other.json' }).devices).toEqual({})
  })

  it('merges all known devices with ETag retries and uses the earliest acknowledgement', async () => {
    const oldAcknowledgement = '2026-01-01T00:00:00.000Z'
    const metadata = JSON.stringify({
      version: 1,
      devices: { old: { deviceId: 'old', lastSeenAt: oldAcknowledgement, acknowledgedThrough: oldAcknowledgement } },
    })
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(new Response('', { status: 404 }))
      .mockResolvedValueOnce(new Response(null, { status: 201 }))
      .mockResolvedValueOnce(new Response(metadata, { status: 200, headers: { ETag: '"m1"' } }))
      .mockResolvedValueOnce(new Response('', { status: 412 }))
      .mockResolvedValueOnce(new Response(metadata, { status: 200, headers: { ETag: '"m2"' } }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)
    await syncWebDav(config)

    expect(fetchMock.mock.calls[3][1].headers['If-Match']).toBe('"m1"')
    expect(fetchMock.mock.calls[5][1].headers['If-Match']).toBe('"m2"')
    expect(JSON.parse(String(fetchMock.mock.calls[5][1].body)).devices).toHaveProperty('old')
    expect(database.compactTombstones).toHaveBeenCalledWith(oldAcknowledgement)
  })

  it('keeps the completed record sync successful when metadata is malformed', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(new Response('', { status: 404 }))
      .mockResolvedValueOnce(new Response(null, { status: 201 }))
      .mockResolvedValueOnce(new Response('{broken', { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    const warning = vi.spyOn(console, 'warn').mockImplementation(() => undefined)
    await expect(syncWebDav(config)).resolves.toBeInstanceOf(Date)
    expect(warning).toHaveBeenCalledOnce()
    expect(database.compactTombstones).not.toHaveBeenCalled()
  })

  it.each([
    [409, '同步目录不存在'],
    [401, '没有写入权限'],
    [403, '没有写入权限'],
    [500, '上传失败'],
  ])('maps upload status %i to an actionable error', async (status, message) => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(new Response('', { status: 404 }))
        .mockResolvedValueOnce(new Response('', { status })),
    )
    await expect(syncWebDav(config)).rejects.toThrow(message)
  })

  it.each([
    [401, '认证失败'],
    [403, '认证失败'],
    [500, '下载失败'],
  ])('maps download status %i to an actionable error', async (status, message) => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('', { status })),
    )
    await expect(syncWebDav(config)).rejects.toThrow(message)
  })

  it('rejects malformed and oversized remote snapshots', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('not json', { status: 200 })),
    )
    await expect(syncWebDav(config)).rejects.toThrow('不是有效的 JSON')
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('', { status: 200, headers: { 'Content-Length': String(21 * 1024 * 1024) } })),
    )
    await expect(syncWebDav(config)).rejects.toThrow('超过 20 MB')
  })

  it('wraps network failures with a stable error code', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => Promise.reject(new Error('offline'))),
    )
    await expect(testWebDav(config)).rejects.toMatchObject({ code: 'NETWORK_FAILED' })
  })

  it('aborts a request that exceeds the timeout', async () => {
    vi.useFakeTimers()
    const fetchMock = vi.fn(
      (_input: string | URL | Request, init?: RequestInit) =>
        new Promise<Response>((_resolve, reject) => {
          init?.signal?.addEventListener('abort', () => {
            const error = new Error('aborted')
            error.name = 'AbortError'
            reject(error)
          })
        }),
    )
    vi.stubGlobal('fetch', fetchMock)
    try {
      const assertion = expect(testWebDav(config)).rejects.toThrow('请求超时')
      await vi.advanceTimersByTimeAsync(30_000)
      await assertion
    } finally {
      vi.useRealTimers()
    }
  })
})
