package com.youhao.fueltrack.data.backup

import android.content.Context
import android.net.Uri
import com.youhao.fueltrack.domain.backup.MAX_BACKUP_CHARS
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Moves backup text in and out of a document the user picked.
 *
 * The Storage Access Framework is used rather than a share sheet pointed at the app cache: the user
 * chooses a real location, the export survives a cache wipe, and no storage permission is required
 * at any API level. The composable owns the picker; this class only does the byte shuffling.
 */
class BackupFiles(private val context: Context) {

    /**
     * Reads a picked document as UTF-8.
     *
     * The character ceiling is enforced *while* reading rather than after, so an oversized or
     * deliberately hostile file cannot exhaust memory before the format check runs. The wording
     * matches the legacy `parseBackup` guard because it is the same rule.
     */
    fun readText(uri: Uri): String {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("无法读取所选文件")
        return stream.use { readCapped(it) }
    }

    fun writeText(uri: Uri, text: String) {
        // "wt" truncates: overwriting an existing document must not leave the tail of the old one.
        val stream = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw IOException("无法写入所选文件")
        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    private fun readCapped(stream: InputStream): String {
        val reader = InputStreamReader(stream, Charsets.UTF_8)
        val builder = StringBuilder()
        val buffer = CharArray(64 * 1024)
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) break
            builder.append(buffer, 0, read)
            if (builder.length > MAX_BACKUP_CHARS) {
                throw IllegalArgumentException("备份文件超过 20 MB，拒绝导入")
            }
        }
        return builder.toString()
    }
}
