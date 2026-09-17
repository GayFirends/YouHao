type SyncItem = { updatedAt: string; deletedAt?: string | null }

function timestamp(value: string) {
  const time = Date.parse(value)
  if (!Number.isFinite(time)) throw new Error('记录更新时间无效，无法合并')
  return time
}

function canonicalItem(item: SyncItem) {
  // SQLite column order and backup field order differ. Compare the same fields
  // in the same order, normalizing equivalent timestamps before the tie-break.
  return JSON.stringify(Object.fromEntries(Object.entries(item)
    .sort(([left], [right]) => left < right ? -1 : left > right ? 1 : 0)
    .map(([key, value]) => [key,
      ['createdAt', 'updatedAt', 'deletedAt'].includes(key) && typeof value === 'string'
        ? new Date(timestamp(value)).toISOString()
        : value,
    ])))
}

export function shouldAcceptRemote<T extends SyncItem>(local: T | undefined, remote: T) {
  const remoteTime = timestamp(remote.updatedAt)
  if (!local) return true
  const localTime = timestamp(local.updatedAt)
  if (remoteTime !== localTime) return remoteTime > localTime
  // Restoring an older copy must not undo a deletion at the same timestamp.
  if (Boolean(remote.deletedAt) !== Boolean(local.deletedAt)) return Boolean(remote.deletedAt)
  return canonicalItem(remote) > canonicalItem(local)
}
