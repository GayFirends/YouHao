import packageInfo from '../../package.json'
import { Capacitor } from '@capacitor/core'
import { database } from './database'

const DEVICE_KEY = 'fuel-track-device-id'

export function getDeviceId() {
  let id = localStorage.getItem(DEVICE_KEY)
  if (!id) {
    id = crypto.randomUUID()
    localStorage.setItem(DEVICE_KEY, id)
  }
  return id
}

export async function createDiagnosticReport() {
  let lastError: unknown = null
  try {
    lastError = JSON.parse(localStorage.getItem('fuel-track-last-error') || 'null')
  } catch {
    lastError = { code: 'DIAGNOSTIC_VALUE_INVALID' }
  }
  return {
    generatedAt: new Date().toISOString(),
    appVersion: packageInfo.version,
    platform: Capacitor.getPlatform(),
    userAgent: navigator.userAgent,
    database: await database.getSchemaInfo(),
    syncFormatVersions: [1, 2],
    deviceId: getDeviceId(),
    lastError,
    privacy: 'This report never includes records, WebDAV credentials, URLs, or encryption passphrases.',
  }
}
