import { Capacitor, registerPlugin } from '@capacitor/core'

interface SecureSessionPlugin {
  get(): Promise<{ value: string | null }>
  set(options: { value: string }): Promise<void>
  remove(): Promise<void>
}

const SecureSession = registerPlugin<SecureSessionPlugin>('SecureSession')

export async function loadRememberedPassphrase() {
  if (!Capacitor.isNativePlatform()) return ''
  return (await SecureSession.get()).value || ''
}

export async function saveRememberedPassphrase(value: string | null) {
  if (!Capacitor.isNativePlatform()) return
  if (value) await SecureSession.set({ value })
  else await SecureSession.remove()
}
