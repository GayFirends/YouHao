import { beforeEach, describe, expect, it, vi } from 'vitest'

const { database } = vi.hoisted(() => ({
  database: {
    mergeData: vi.fn(async () => undefined),
    exportData: vi.fn(async () => ({ version: 1 as const, exportedAt: '', vehicles: [], records: [] })),
  },
}))

vi.mock('../database', () => ({ database }))

import { syncWebDav, testWebDav } from '../webdav'

const config = { url: 'https://dav.example.com/fuel', username: 'user', password: 'pass', fileName: 'data.json' }
const payload = JSON.stringify({ version: 1, exportedAt: '', vehicles: [], records: [] })

describe('WebDAV synchronization', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('tests the configured directory instead of the data file', async () => {
    const fetchMock = vi.fn(async () => new Response('', { status: 207 }))
    vi.stubGlobal('fetch', fetchMock)
    await testWebDav(config)
    expect(fetchMock).toHaveBeenCalledWith('https://dav.example.com/fuel/', expect.objectContaining({ method: 'PROPFIND' }))
  })

  it('downloads and retries when an ETag conflict occurs', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(payload, { status: 200, headers: { ETag: '"v1"' } }))
      .mockResolvedValueOnce(new Response('', { status: 412 }))
      .mockResolvedValueOnce(new Response(payload, { status: 200, headers: { ETag: '"v2"' } }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    await syncWebDav(config)

    expect(fetchMock).toHaveBeenCalledTimes(4)
    expect(fetchMock.mock.calls[1][1].headers['If-Match']).toBe('"v1"')
    expect(fetchMock.mock.calls[3][1].headers['If-Match']).toBe('"v2"')
    expect(database.mergeData).toHaveBeenCalledTimes(2)
  })
})
