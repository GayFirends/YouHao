package com.youhao.fueltrack.domain.error

/**
 * Port of `src/services/app-error.ts`. The code drives the recovery hint shown to the user;
 * the diagnostic report records the code, never the message payload.
 */
enum class AppErrorCode {
    DATABASE_CORRUPT,
    DATABASE_MIGRATION_FAILED,
    DATABASE_WRITE_FAILED,
    SYNC_CONFLICT,
    SYNC_AUTH_FAILED,
    SYNC_TIMEOUT,
    SYNC_FORMAT_INVALID,
    SYNC_ENCRYPTION_FAILED,
    NETWORK_FAILED,
    UNKNOWN,
    ;

    val guidance: String
        get() = when (this) {
            DATABASE_CORRUPT -> "请先导出可恢复数据，再从安全快照或备份恢复。"
            DATABASE_MIGRATION_FAILED -> "升级未完成，原数据已保留。请重启应用后重试。"
            DATABASE_WRITE_FAILED -> "请检查设备剩余空间，然后重试。"
            SYNC_CONFLICT -> "云端持续发生并发修改，请稍后重新同步。"
            SYNC_AUTH_FAILED -> "请检查 WebDAV 用户名、应用密码和目录权限。"
            SYNC_TIMEOUT -> "请检查网络和 WebDAV 服务状态后重试。"
            SYNC_FORMAT_INVALID -> "请勿继续覆盖云端文件，确认客户端版本或从备份恢复。"
            SYNC_ENCRYPTION_FAILED -> "请确认同步口令正确且云端文件未损坏。"
            NETWORK_FAILED -> "请检查网络连接后重试。"
            UNKNOWN -> "请重试；若问题持续，请导出诊断信息。"
        }

    /** Stable wire name used by the persisted diagnostic report. */
    val wireName: String get() = name
}

class AppException(
    val code: AppErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

fun Throwable.toAppException(fallback: AppErrorCode = AppErrorCode.UNKNOWN): AppException =
    this as? AppException ?: AppException(fallback, message ?: "发生未知错误", this)

/**
 * Port of the `fuel-track-last-error` record. The legacy app kept this in localStorage so the
 * diagnostic report could describe the most recent failure; only the code is kept, never the
 * message, because messages can quote URLs or credentials.
 */
data class LastError(
    val code: AppErrorCode,
    val occurredAt: String,
)

/** e.g. `同步失败 请检查 WebDAV 用户名、应用密码和目录权限。` */
fun userErrorMessage(error: Throwable, fallback: AppErrorCode = AppErrorCode.UNKNOWN): String {
    val normalized = error.toAppException(fallback)
    return "${normalized.message} ${normalized.code.guidance}"
}
