type SyncItem = { updatedAt: string }

export function shouldAcceptRemote<T extends SyncItem>(local: T | undefined, remote: T) {
  if (!local) return true
  if (remote.updatedAt !== local.updatedAt) return remote.updatedAt > local.updatedAt
  // Equal timestamps can happen when clocks are coarse or data is restored twice.
  // A deterministic tie-breaker makes every device converge on the same value.
  return JSON.stringify(remote) > JSON.stringify(local)
}
