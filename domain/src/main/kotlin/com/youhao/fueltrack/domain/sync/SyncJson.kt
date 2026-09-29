package com.youhao.fueltrack.domain.sync

import kotlinx.serialization.json.Json

/**
 * Wire JSON for sync documents and for conflict rows stored in the database.
 *
 * `encodeDefaults` is mandatory here. `version`, `encrypted`, `compression`, `algorithm` and `kdf`
 * all carry defaults, so omitting them would emit envelopes and payloads that
 * [SyncPayloadValidator] rejects when they come back from the server.
 */
val SyncJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}
