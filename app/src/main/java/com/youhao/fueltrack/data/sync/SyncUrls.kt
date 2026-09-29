package com.youhao.fueltrack.data.sync

import com.youhao.fueltrack.data.prefs.DEFAULT_SYNC_FILE_NAME
import com.youhao.fueltrack.domain.model.WebDavConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * URL construction, ported from `syncTarget` / `directoryUrl` / `fileUrl` / `metadataUrl` in
 * `src/services/webdav.ts`.
 *
 * The legacy code stripped trailing slashes and then concatenated, applying `encodeURIComponent`
 * to the file name. `HttpUrl.addPathSegment` does the same job with correct percent-encoding for
 * arbitrary names.
 */
object SyncUrls {

    /** The effective file name; the legacy `getConfig()` default was `fuel-track.json`. */
    fun fileName(config: WebDavConfig): String =
        config.fileName.ifBlank { DEFAULT_SYNC_FILE_NAME }

    private fun base(config: WebDavConfig): HttpUrl = config.url.trimEnd('/').toHttpUrl()

    /** The directory the sync file lives in, with a trailing slash (used by PROPFIND). */
    fun directory(config: WebDavConfig): HttpUrl =
        base(config).newBuilder().addPathSegment("").build()

    fun syncFile(config: WebDavConfig): HttpUrl =
        base(config).newBuilder().addPathSegment(fileName(config)).build()

    /** `<file>.meta.json`, the companion document holding per-device sync acknowledgements. */
    fun metadataFile(config: WebDavConfig): HttpUrl =
        base(config).newBuilder().addPathSegment(fileName(config) + ".meta.json").build()

    /**
     * The per-target key for the stored sync base and metadata cache. The legacy app keyed those on
     * `syncTarget(config)`; the normalised URL is the same value, minus the encoding difference.
     */
    fun target(config: WebDavConfig): String = syncFile(config).toString()
}
