import { describe, expect, it } from 'vitest'
import type { SyncPayload } from '../../types'
import { decryptSyncDocument, encryptSyncPayload } from '../sync-crypto'

const payload: SyncPayload = {
  version: 1,
  exportedAt: '2026-09-18T00:00:00.000Z',
  vehicles: [],
  records: [],
}

describe('encrypted sync documents', () => {
  it('round-trips a v1 payload through the v2 envelope', async () => {
    const encrypted = await encryptSyncPayload(payload, 'correct horse battery staple')
    expect(encrypted).toMatchObject({ version: 2, encrypted: true, compression: 'gzip' })
    expect(await decryptSyncDocument(encrypted, 'correct horse battery staple')).toEqual(payload)
  })

  it('continues to accept plaintext v1 documents', async () => {
    expect(await decryptSyncDocument(payload, '')).toEqual(payload)
  })

  it('rejects a wrong passphrase and tampered parameters', async () => {
    const encrypted = await encryptSyncPayload(payload, 'correct horse battery staple')
    await expect(decryptSyncDocument(encrypted, 'wrong password')).rejects.toMatchObject({ code: 'SYNC_ENCRYPTION_FAILED' })
    await expect(
      decryptSyncDocument({ ...encrypted, crypto: { ...encrypted.crypto, algorithm: 'unknown' } }, 'correct horse battery staple'),
    ).rejects.toMatchObject({ code: 'SYNC_FORMAT_INVALID' })
  })
})
