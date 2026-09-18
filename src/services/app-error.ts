export type AppErrorCode =
  | 'DATABASE_CORRUPT'
  | 'DATABASE_MIGRATION_FAILED'
  | 'DATABASE_WRITE_FAILED'
  | 'SYNC_CONFLICT'
  | 'SYNC_AUTH_FAILED'
  | 'SYNC_TIMEOUT'
  | 'SYNC_FORMAT_INVALID'
  | 'SYNC_ENCRYPTION_FAILED'
  | 'NETWORK_FAILED'
  | 'UNKNOWN'

const guidance: Record<AppErrorCode, string> = {
  DATABASE_CORRUPT: '请先导出可恢复数据，再从安全快照或备份恢复。',
  DATABASE_MIGRATION_FAILED: '升级未完成，原数据已保留。请重启应用后重试。',
  DATABASE_WRITE_FAILED: '请检查设备剩余空间，然后重试。',
  SYNC_CONFLICT: '云端持续发生并发修改，请稍后重新同步。',
  SYNC_AUTH_FAILED: '请检查 WebDAV 用户名、应用密码和目录权限。',
  SYNC_TIMEOUT: '请检查网络和 WebDAV 服务状态后重试。',
  SYNC_FORMAT_INVALID: '请勿继续覆盖云端文件，确认客户端版本或从备份恢复。',
  SYNC_ENCRYPTION_FAILED: '请确认同步口令正确且云端文件未损坏。',
  NETWORK_FAILED: '请检查网络连接后重试。',
  UNKNOWN: '请重试；若问题持续，请导出诊断信息。',
}

export class AppError extends Error {
  constructor(
    public readonly code: AppErrorCode,
    message: string,
    options?: ErrorOptions,
  ) {
    super(message, options)
    this.name = 'AppError'
  }
}

export function toAppError(error: unknown, fallback: AppErrorCode = 'UNKNOWN') {
  if (error instanceof AppError) return error
  const message = error instanceof Error ? error.message : '发生未知错误'
  return new AppError(fallback, message, error instanceof Error ? { cause: error } : undefined)
}

export function userErrorMessage(error: unknown) {
  const normalized = toAppError(error)
  try {
    localStorage.setItem('fuel-track-last-error', JSON.stringify({ code: normalized.code, occurredAt: new Date().toISOString() }))
  } catch {
    // Diagnostics must never hide the original error when storage is unavailable.
  }
  return `${normalized.message} ${guidance[normalized.code]}`
}
