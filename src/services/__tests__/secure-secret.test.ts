import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  native: false,
  get: vi.fn(async () => ({ value: null as string | null })),
  set: vi.fn(async () => undefined),
  remove: vi.fn(async () => undefined),
}))

vi.mock('@capacitor/core', () => ({
  Capacitor: { isNativePlatform: () => mocks.native },
  registerPlugin: () => ({ get: mocks.get, set: mocks.set, remove: mocks.remove }),
}))

import { loadRememberedPassphrase, saveRememberedPassphrase } from '../secure-secret'

describe('secure encryption-passphrase storage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.native = false
  })

  it('never invokes persistent storage on the web', async () => {
    expect(await loadRememberedPassphrase()).toBe('')
    await saveRememberedPassphrase('secret passphrase')
    expect(mocks.get).not.toHaveBeenCalled()
    expect(mocks.set).not.toHaveBeenCalled()
  })

  it('reads, writes and removes the Android Keystore-backed value', async () => {
    mocks.native = true
    mocks.get.mockResolvedValueOnce({ value: 'remembered passphrase' })
    expect(await loadRememberedPassphrase()).toBe('remembered passphrase')
    await saveRememberedPassphrase('new passphrase')
    expect(mocks.set).toHaveBeenCalledWith({ value: 'new passphrase' })
    await saveRememberedPassphrase(null)
    expect(mocks.remove).toHaveBeenCalledOnce()
  })
})
