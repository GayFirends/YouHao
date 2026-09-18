import { gzipSync } from 'node:zlib'
import { readFileSync, readdirSync } from 'node:fs'

const html = readFileSync('dist/index.html', 'utf8')
const entry = html.match(/src="\.\/assets\/(index-[^"]+\.js)"/)?.[1]
if (!entry) throw new Error('Unable to find the production entry bundle')
const entryGzip = gzipSync(readFileSync(`dist/assets/${entry}`)).byteLength
if (entryGzip > 100 * 1024) throw new Error(`Entry JS gzip size ${entryGzip} exceeds the 100 KiB budget`)

const wasm = readdirSync('dist/assets').find((name) => name.startsWith('sql-wasm-') && name.endsWith('.wasm'))
if (!wasm) throw new Error('Unable to find sql.js WASM output')
const wasmBytes = readFileSync(`dist/assets/${wasm}`).byteLength
if (wasmBytes > 724_251) throw new Error(`SQLite WASM size ${wasmBytes} exceeds the 10% growth budget`)

console.log(`Bundle budgets passed: entry ${entryGzip} bytes gzip; SQLite WASM ${wasmBytes} bytes`)
