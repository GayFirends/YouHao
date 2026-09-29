// Node's TypeScript type-stripping loader requires explicit file extensions on relative
// imports, while the legacy sources use extensionless specifiers. This hook restores the
// TypeScript-style resolution so the original `.ts` modules can be imported unmodified —
// which is the whole point: the fixture must be produced by the shipped implementation,
// not by a reimplementation of it.
const CANDIDATE_EXTENSIONS = ['.ts', '.mts', '.js', '.mjs']

export async function resolve(specifier, context, nextResolve) {
  if (specifier.startsWith('.') && !/\.[cm]?[jt]s$/.test(specifier)) {
    for (const extension of CANDIDATE_EXTENSIONS) {
      try {
        return await nextResolve(specifier + extension, context)
      } catch {
        // Fall through to the next candidate extension.
      }
    }
  }
  return nextResolve(specifier, context)
}
