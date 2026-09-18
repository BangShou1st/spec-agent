/**
 * Vendors the OCR runtime (worker + WASM core) into `public/tesseract/`.
 *
 * Why: the built-in OCR path must work without reaching a CDN. tesseract.js
 * loads its worker and core by URL, and the core loads a sibling `.wasm` file,
 * so both files have to sit next to each other in a directory the dev server
 * actually serves. Copying them out of node_modules on `predev` / `prebuild`
 * keeps ~4MB of WASM out of git while still being fully offline at runtime.
 *
 * The language data is different: it is not shipped by any dependency we use
 * (`@tesseract.js-data/*` is ~21MB per language), so `public/tessdata/*.gz`
 * holds the small "fast" models (chi_sim + eng, ~3.7MB total) and IS committed.
 *
 * Idempotent: files with an identical size are left alone. `--force` re-copies.
 */
import { copyFile, mkdir, stat } from 'node:fs/promises'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const root = resolve(here, '..')
const outDir = join(root, 'public', 'tesseract')

const assets = [
  ['tesseract.js/dist/worker.min.js', 'worker.min.js'],
  ['tesseract.js-core/tesseract-core-simd-lstm.wasm.js', 'tesseract-core-simd-lstm.wasm.js'],
  ['tesseract.js-core/tesseract-core-simd-lstm.wasm', 'tesseract-core-simd-lstm.wasm'],
]

const force = process.argv.includes('--force')

async function sizeOf(path) {
  try {
    return (await stat(path)).size
  } catch {
    return -1
  }
}

async function main() {
  await mkdir(outDir, { recursive: true })
  let copied = 0
  for (const [from, to] of assets) {
    const source = join(root, 'node_modules', from)
    const target = join(outDir, to)
    const sourceSize = await sizeOf(source)
    if (sourceSize < 0) {
      console.warn(`[vendor-ocr] missing dependency file, skipped: ${from}`)
      continue
    }
    if (!force && (await sizeOf(target)) === sourceSize) continue
    await copyFile(source, target)
    copied += 1
  }
  console.log(
    copied === 0
      ? '[vendor-ocr] OCR runtime already vendored in public/tesseract'
      : `[vendor-ocr] vendored ${copied} file(s) into public/tesseract`,
  )
}

main().catch((error) => {
  console.error('[vendor-ocr] failed', error)
  process.exitCode = 1
})
