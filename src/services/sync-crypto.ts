import type { EncryptedSyncEnvelopeV2, SyncDocument, SyncPayloadV1 } from '../types'
import { AppError } from './app-error'
import { validateSyncPayload } from './sync-validation'

export const PBKDF2_ITERATIONS = 310_000

function toBase64(bytes: Uint8Array) {
  let binary = ''
  for (let index = 0; index < bytes.length; index += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(index, index + 0x8000))
  }
  return btoa(binary)
}

function fromBase64(value: string) {
  try {
    const binary = atob(value)
    return Uint8Array.from(binary, (character) => character.charCodeAt(0))
  } catch {
    throw new AppError('SYNC_FORMAT_INVALID', '加密同步文件包含无效编码')
  }
}

async function gzip(bytes: Uint8Array) {
  const stream = new Blob([bytes]).stream().pipeThrough(new CompressionStream('gzip'))
  return new Uint8Array(await new Response(stream).arrayBuffer())
}

async function gunzip(bytes: Uint8Array) {
  const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip'))
  return new Uint8Array(await new Response(stream).arrayBuffer())
}

async function deriveKey(passphrase: string, salt: Uint8Array, iterations: number) {
  const material = await crypto.subtle.importKey('raw', new TextEncoder().encode(passphrase), 'PBKDF2', false, ['deriveKey'])
  return crypto.subtle.deriveKey({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations }, material, { name: 'AES-GCM', length: 256 }, false, [
    'encrypt',
    'decrypt',
  ])
}

export function isEncryptedSyncDocument(value: unknown): value is EncryptedSyncEnvelopeV2 {
  if (!value || typeof value !== 'object') return false
  const item = value as Record<string, unknown>
  return item.version === 2 && item.encrypted === true
}

function validateEnvelope(value: unknown): EncryptedSyncEnvelopeV2 {
  if (!isEncryptedSyncDocument(value)) throw new AppError('SYNC_FORMAT_INVALID', '不支持的同步文件版本')
  const item = value as unknown as EncryptedSyncEnvelopeV2
  if (item.crypto?.algorithm !== 'AES-GCM' || item.crypto.kdf !== 'PBKDF2-SHA-256' || item.compression !== 'gzip') {
    throw new AppError('SYNC_FORMAT_INVALID', '不支持的同步加密算法')
  }
  if (!Number.isSafeInteger(item.crypto.iterations) || item.crypto.iterations < 100_000 || item.crypto.iterations > 2_000_000) {
    throw new AppError('SYNC_FORMAT_INVALID', '同步文件的密钥派生参数无效')
  }
  if (
    typeof item.createdAt !== 'string' ||
    typeof item.crypto.salt !== 'string' ||
    typeof item.crypto.iv !== 'string' ||
    typeof item.ciphertext !== 'string'
  ) {
    throw new AppError('SYNC_FORMAT_INVALID', '加密同步文件结构不完整')
  }
  return item
}

export async function encryptSyncPayload(payload: SyncPayloadV1, passphrase: string): Promise<EncryptedSyncEnvelopeV2> {
  if (passphrase.length < 8) throw new AppError('SYNC_ENCRYPTION_FAILED', '同步口令至少需要 8 个字符')
  const salt = crypto.getRandomValues(new Uint8Array(16))
  const iv = crypto.getRandomValues(new Uint8Array(12))
  const key = await deriveKey(passphrase, salt, PBKDF2_ITERATIONS)
  const compressed = await gzip(new TextEncoder().encode(JSON.stringify(payload)))
  const ciphertext = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, compressed)
  return {
    version: 2,
    encrypted: true,
    createdAt: new Date().toISOString(),
    crypto: {
      algorithm: 'AES-GCM',
      kdf: 'PBKDF2-SHA-256',
      iterations: PBKDF2_ITERATIONS,
      salt: toBase64(salt),
      iv: toBase64(iv),
    },
    compression: 'gzip',
    ciphertext: toBase64(new Uint8Array(ciphertext)),
  }
}

export async function decryptSyncDocument(document: SyncDocument | unknown, passphrase: string): Promise<SyncPayloadV1> {
  if (!isEncryptedSyncDocument(document)) return validateSyncPayload(document)
  if (!passphrase) throw new AppError('SYNC_ENCRYPTION_FAILED', '云端文件已加密，请输入同步口令')
  const envelope = validateEnvelope(document)
  try {
    const salt = fromBase64(envelope.crypto.salt)
    const iv = fromBase64(envelope.crypto.iv)
    if (salt.length !== 16 || iv.length !== 12) throw new Error('invalid parameters')
    const key = await deriveKey(passphrase, salt, envelope.crypto.iterations)
    const plaintext = await crypto.subtle.decrypt({ name: 'AES-GCM', iv }, key, fromBase64(envelope.ciphertext))
    const decompressed = await gunzip(new Uint8Array(plaintext))
    return validateSyncPayload(JSON.parse(new TextDecoder().decode(decompressed)))
  } catch (error) {
    if (error instanceof AppError) throw error
    throw new AppError('SYNC_ENCRYPTION_FAILED', '同步口令错误或云端文件已损坏', { cause: error })
  }
}
