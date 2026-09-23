/**
 * Local document → plain text extraction for resource attachments.
 *
 * WHY LOCAL: the model transport in this project is plain-text chat
 * completions (`ChatCompletionsProtocolAdapter` writes
 * `content: <string>`), so no PDF/Office binary ever reaches the model. The
 * text has to exist BEFORE the model call, and the resource node already
 * stores exactly that: `content.text`. So extraction is deliberately
 * deterministic and dependency-light — no parsing model, no server round trip,
 * no non-reproducible output.
 *
 * ROUTING:
 *  - `.pdf`                 → pdfjs text layer (fast, exact for digital PDFs)
 *  - `.pdf` with no text    → OCR fallback (scanned pages)
 *  - `.docx`                → OPC zip → `word/document.xml` paragraphs
 *  - `.xlsx` / `.xlsm`      → OPC zip → sharedStrings + sheet cell values
 *  - images                 → OCR
 *  - `.txt` / `.md`         → raw text
 *
 * Everything is caps-and-fails-visibly: a document that yields no text is
 * reported as such instead of silently attaching an empty resource.
 */
import JSZip from 'jszip'

/** Upper bound of the attached text. Matches the browser-side read limit. */
export const MAX_EXTRACTED_TEXT_BYTES = 256 * 1024

export type ExtractKind = 'text' | 'pdf' | 'docx' | 'xlsx' | 'image'

export interface ExtractedDocument {
  /** Plain text that goes into `content.text`. */
  text: string
  kind: ExtractKind
  /** True when the text came out of OCR rather than a text layer. */
  ocr: boolean
  /** PDF page count / sheet names, when meaningful. */
  pages?: number
  sheets?: string[]
  /** User-visible caveats (truncated, empty page, OCR language, …). */
  warnings: string[]
}

export class DocumentExtractionError extends Error {
  readonly code: string

  constructor(code: string, message: string) {
    super(message)
    this.name = 'DocumentExtractionError'
    this.code = code
  }
}

const PDF_EXTENSIONS = ['.pdf']
const DOCX_EXTENSIONS = ['.docx']
const XLSX_EXTENSIONS = ['.xlsx', '.xlsm']
const TEXT_EXTENSIONS = ['.txt', '.md', '.markdown', '.csv']
const IMAGE_EXTENSIONS = ['.png', '.jpg', '.jpeg', '.webp', '.bmp']

export function extensionOf(name: string): string {
  const dot = name.lastIndexOf('.')
  return dot < 0 ? '' : name.slice(dot).toLowerCase()
}

/** OCR needs a raster image; PDF pages are rasterised through pdfjs first. */
export function isOcrCapable(fileName: string): boolean {
  return IMAGE_EXTENSIONS.includes(extensionOf(fileName))
}

export function isSupportedDocument(fileName: string): boolean {
  const extension = extensionOf(fileName)
  return PDF_EXTENSIONS.includes(extension)
    || DOCX_EXTENSIONS.includes(extension)
    || XLSX_EXTENSIONS.includes(extension)
    || TEXT_EXTENSIONS.includes(extension)
    || IMAGE_EXTENSIONS.includes(extension)
}

export function unsupportedDocumentMessage(fileName: string): string {
  return `${fileName} 暂不支持解析。可直接解析：PDF、Word(.docx)、Excel(.xlsx)、纯文本(.txt/.md/.csv)、图片(OCR)`
}

/**
 * Blob reading helpers.
 *
 * Deliberately FileReader-based rather than `Blob.text()` / `.arrayBuffer()`:
 * both are missing in the jsdom environment the unit tests run in, and
 * FileReader is supported everywhere the app runs.
 */
function readBlobAsText(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(typeof reader.result === 'string' ? reader.result : '')
    reader.onerror = () => reject(reader.error ?? new Error('读取文件失败'))
    reader.readAsText(blob)
  })
}

function readBlobAsBytes(blob: Blob): Promise<Uint8Array> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(new Uint8Array(reader.result as ArrayBuffer))
    reader.onerror = () => reject(reader.error ?? new Error('读取文件失败'))
    reader.readAsArrayBuffer(blob)
  })
}

function decodeXmlEntities(value: string): string {
  return value
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&#(\d+);/g, (_match, code: string) => String.fromCodePoint(Number(code)))
    .replace(/&#x([0-9a-fA-F]+);/g, (_match, code: string) => String.fromCodePoint(parseInt(code, 16)))
    .replace(/&amp;/g, '&')
}

function normalizeLines(text: string): string {
  return text
    .replace(/\r\n?/g, '\n')
    .replace(/[ \t]+\n/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}

function enforceLimit(text: string, warnings: string[]): string {
  const encoder = new TextEncoder()
  if (encoder.encode(text).length <= MAX_EXTRACTED_TEXT_BYTES) return text
  // Slice on characters, then verify the byte budget (Chinese is 3 bytes/char).
  let cut = Math.floor((text.length * MAX_EXTRACTED_TEXT_BYTES) / encoder.encode(text).length)
  while (cut > 0 && encoder.encode(text.slice(0, cut)).length > MAX_EXTRACTED_TEXT_BYTES) {
    cut = Math.floor(cut * 0.95)
  }
  warnings.push(`文档较长，已截断到约 ${Math.round(MAX_EXTRACTED_TEXT_BYTES / 1024)}KB`)
  return text.slice(0, cut)
}

/** pdf.js text items are `{str}` for text; other item kinds carry no text. */
function pdfItemText(item: unknown): string {
  if (typeof item !== 'object' || item === null) return ''
  const str = (item as { str?: unknown }).str
  return typeof str === 'string' ? str : ''
}

/**
 * WordprocessingML → text. Paragraph boundaries, in-paragraph line breaks and
 * tabs are the only structure we honour, and they have to be tokenised TOGETHER
 * with the text runs: a `<w:tab/>` / `<w:br/>` is a sibling of `<w:t>`, never
 * inside it, so "replace tabs first, then read `<w:t>`" silently drops them.
 * Every run's own wording is kept verbatim.
 */
export function docxXmlToText(documentXml: string): string {
  const tokens = /<w:t(?:\s[^>]*)?>([\s\S]*?)<\/w:t>|<w:tab\b[^>]*\/>|<w:br\b[^>]*\/>/g
  const lines: string[] = []
  for (const paragraph of documentXml.split(/<\/w:p>/)) {
    if (!/<w:t[\s>]/.test(paragraph) && !/<w:(?:tab|br)\b/.test(paragraph)) continue
    let line = ''
    for (const match of paragraph.matchAll(tokens)) {
      if (match[1] !== undefined) line += decodeXmlEntities(match[1])
      else line += match[0].startsWith('<w:br') ? '\n' : '\t'
    }
    lines.push(line)
  }
  return normalizeLines(lines.join('\n'))
}

interface SheetPart {
  name: string
  path: string
}

/**
 * SpreadsheetML → text. Only values are extracted (no formulas/styles): the
 * point is a faithful, bounded reading of what the sheet displays. Each row
 * becomes one tab-separated line and each sheet gets a header line.
 */
export function xlsxSheetsFromWorkbook(workbookXml: string, relsXml: string): SheetPart[] {
  const rels = new Map<string, string>()
  for (const match of relsXml.matchAll(/<Relationship\b[^>]*>/g)) {
    const tag = match[0]
    const id = /\bId="([^"]+)"/.exec(tag)?.[1]
    const target = /\bTarget="([^"]+)"/.exec(tag)?.[1]
    if (id && target) rels.set(id, target.replace(/^\/?xl\//, '').replace(/^\.\.\//, ''))
  }
  const sheets: SheetPart[] = []
  for (const match of workbookXml.matchAll(/<sheet\b[^>]*\/?>/g)) {
    const tag = match[0]
    const name = decodeXmlEntities(/\bname="([^"]*)"/.exec(tag)?.[1] ?? 'Sheet')
    const relId = /\br:id="([^"]+)"/.exec(tag)?.[1]
    const target = relId ? rels.get(relId) : undefined
    if (target) sheets.push({ name, path: `xl/${target}` })
  }
  return sheets
}

function cellsFromRow(rowXml: string, sharedStrings: string[]): string[] {
  const cells: string[] = []
  // Self-closing cells (`<c r="B2"/>`) MUST be matched before the paired form:
  // otherwise `[^>]*` swallows the `/` and the non-greedy body runs on to the
  // next `</c>`, silently merging two cells into one.
  for (const match of rowXml.matchAll(/<c\b([^>]*?)\/>|<c\b([^>]*)>([\s\S]*?)<\/c>/g)) {
    const attributes = match[1] ?? match[2] ?? ''
    const inner = match[3] ?? ''
    const type = /\bt="([^"]+)"/.exec(attributes)?.[1]
    if (type === 'inlineStr') {
      const inline = [...inner.matchAll(/<t(?:\s[^>]*)?>([\s\S]*?)<\/t>/g)]
        .map((entry) => decodeXmlEntities(entry[1]))
        .join('')
      cells.push(inline)
      continue
    }
    const raw = /<v>([\s\S]*?)<\/v>/.exec(inner)?.[1]
    if (raw === undefined) {
      cells.push('')
      continue
    }
    if (type === 's') {
      const index = Number(raw)
      cells.push(sharedStrings[index] ?? '')
      continue
    }
    if (type === 'str') {
      cells.push(decodeXmlEntities(raw))
      continue
    }
    cells.push(decodeXmlEntities(raw))
  }
  return cells
}

export function xlsxSheetPartToText(sheetXml: string, sharedStrings: string[]): string {
  const lines: string[] = []
  for (const rowMatch of sheetXml.matchAll(/<row\b[^>]*>([\s\S]*?)<\/row>/g)) {
    const cells = cellsFromRow(rowMatch[1], sharedStrings)
    if (cells.every((cell) => cell.trim() === '')) continue
    lines.push(cells.join('\t').replace(/\t+$/, ''))
  }
  return lines.join('\n')
}

function sharedStringsFromXml(xml: string | undefined): string[] {
  if (!xml) return []
  const entries: string[] = []
  for (const match of xml.matchAll(/<si>([\s\S]*?)<\/si>/g)) {
    const parts = [...match[1].matchAll(/<t(?:\s[^>]*)?>([\s\S]*?)<\/t>/g)]
    entries.push(parts.map((part) => decodeXmlEntities(part[1])).join(''))
  }
  return entries
}

async function extractDocx(file: File): Promise<ExtractedDocument> {
  const zip = await JSZip.loadAsync(file)
  const documentXml = await zip.file('word/document.xml')?.async('string')
  if (!documentXml) {
    throw new DocumentExtractionError('DOCX_NO_BODY', '无法读取 Word 正文（word/document.xml 缺失）')
  }
  const text = docxXmlToText(documentXml)
  const warnings: string[] = []
  if (!text) warnings.push('该 Word 文档没有可提取的正文文本')
  return { text: enforceLimit(text, warnings), kind: 'docx', ocr: false, warnings }
}

async function extractXlsx(file: File): Promise<ExtractedDocument> {
  const zip = await JSZip.loadAsync(file)
  const workbookXml = await zip.file('xl/workbook.xml')?.async('string')
  const relsXml = await zip.file('xl/_rels/workbook.xml.rels')?.async('string')
  if (!workbookXml || !relsXml) {
    throw new DocumentExtractionError('XLSX_NO_WORKBOOK', '无法读取 Excel 工作簿（xl/workbook.xml 缺失）')
  }
  const sharedStrings = sharedStringsFromXml(await zip.file('xl/sharedStrings.xml')?.async('string'))
  const sheets = xlsxSheetsFromWorkbook(workbookXml, relsXml)
  const warnings: string[] = []
  const blocks: string[] = []
  const usedSheets: string[] = []
  for (const sheet of sheets) {
    const sheetXml = await zip.file(sheet.path)?.async('string')
    if (!sheetXml) {
      warnings.push(`工作表「${sheet.name}」读取失败，已跳过`)
      continue
    }
    const body = xlsxSheetPartToText(sheetXml, sharedStrings)
    usedSheets.push(sheet.name)
    blocks.push(`# ${sheet.name}\n${body}`)
  }
  const text = normalizeLines(blocks.join('\n\n'))
  if (!text) warnings.push('该 Excel 工作簿没有可提取的文本内容')
  return {
    text: enforceLimit(text, warnings),
    kind: 'xlsx',
    ocr: false,
    sheets: usedSheets,
    warnings,
  }
}

/** The slice of pdfjs this module actually uses (keeps the seam narrow). */
export interface PdfPageLike {
  getTextContent: () => Promise<{ items: unknown[] }>
  getViewport: (options: { scale: number }) => { width: number; height: number }
  render: (options: {
    canvasContext: CanvasRenderingContext2D
    viewport: unknown
  }) => { promise: Promise<void> }
}

export interface PdfDocumentLike {
  numPages: number
  getPage: (pageNumber: number) => Promise<PdfPageLike>
}

export interface PdfjsLike {
  getDocument: (options: { data: Uint8Array; isEvalSupported: boolean }) => {
    promise: Promise<PdfDocumentLike>
  }
  GlobalWorkerOptions: { workerSrc: string }
}

/**
 * pdfjs is imported lazily: it pulls in a ~1MB parser + worker that most
 * sessions never touch (txt/md/docx/xlsx/OCR paths do not need it), and the
 * library must only ever run in the browser.
 */
async function loadPdfjsFromBundle(): Promise<PdfjsLike> {
  const pdfjs = await import('pdfjs-dist')
  const workerUrl = (await import('pdfjs-dist/build/pdf.worker.min.mjs?url')).default
  pdfjs.GlobalWorkerOptions.workerSrc = workerUrl
  return pdfjs as unknown as PdfjsLike
}

/**
 * Test seams.
 *
 * The PDF page loop and the "no text layer → OCR" decision are this module's
 * own logic and are unit-tested through these overrides; the real pdf.js
 * parser and the real tesseract worker only run in a browser, so they are
 * covered by the in-browser probe instead. Production never calls this.
 */
let loadPdfjsImpl: () => Promise<PdfjsLike> = loadPdfjsFromBundle
let runOcrImpl: (source: Blob | HTMLCanvasElement, onProgress?: (message: string) => void) => Promise<OcrOutcome> =
  (source, onProgress) => runOcr(source, onProgress)

export function configureDocumentExtractorsForTests(overrides: {
  loadPdfjs?: () => Promise<PdfjsLike>
  runOcr?: typeof runOcrImpl
}): void {
  if (overrides.loadPdfjs) loadPdfjsImpl = overrides.loadPdfjs
  if (overrides.runOcr) runOcrImpl = overrides.runOcr
}

async function extractPdf(
  file: File,
  onProgress?: (message: string) => void,
): Promise<ExtractedDocument> {
  const pdfjs = await loadPdfjsImpl()
  const data = await readBlobAsBytes(file)
  const document = await pdfjs.getDocument({ data, isEvalSupported: false }).promise
  const warnings: string[] = []
  const pageTexts: string[] = []
  const emptyPages: number[] = []
  for (let pageNumber = 1; pageNumber <= document.numPages; pageNumber += 1) {
    onProgress?.(`解析 PDF 第 ${pageNumber}/${document.numPages} 页…`)
    const page = await document.getPage(pageNumber)
    const content = await page.getTextContent()
    const text = content.items
      .map((item) => pdfItemText(item))
      .join(' ')
    if (text.trim()) pageTexts.push(text)
    else emptyPages.push(pageNumber)
  }
  const hasTextLayer = pageTexts.length > 0
  if (hasTextLayer) {
    if (emptyPages.length > 0) {
      warnings.push(`第 ${emptyPages.join('、')} 页没有文本层（可能是扫描页），未被提取`)
    }
    const text = normalizeLines(pageTexts.join('\n\n'))
    return {
      text: enforceLimit(text, warnings),
      kind: 'pdf',
      ocr: false,
      pages: document.numPages,
      warnings,
    }
  }
  // No text layer at all → almost certainly a scan. Fall back to OCR.
  onProgress?.('未发现文本层，改用 OCR 识别扫描件…')
  const ocrText = await ocrPdfPages(document, document.numPages, onProgress, warnings)
  const text = normalizeLines(ocrText)
  if (!text) warnings.push('OCR 未能从该 PDF 中识别出文本')
  return {
    text: enforceLimit(text, warnings),
    kind: 'pdf',
    ocr: true,
    pages: document.numPages,
    warnings,
  }
}

async function ocrPdfPages(
  document: PdfDocumentLike,
  numPages: number,
  onProgress: ((message: string) => void) | undefined,
  warnings: string[],
): Promise<string> {
  const results: string[] = []
  for (let pageNumber = 1; pageNumber <= numPages; pageNumber += 1) {
    onProgress?.(`OCR 识别第 ${pageNumber}/${numPages} 页…`)
    const page = await document.getPage(pageNumber)
    const viewport = page.getViewport({ scale: 2 })
    const canvas = window.document.createElement('canvas')
    canvas.width = Math.ceil(viewport.width)
    canvas.height = Math.ceil(viewport.height)
    const context = canvas.getContext('2d')
    if (!context) {
      warnings.push('当前浏览器无法渲染 PDF 页面，OCR 已跳过')
      return results.join('\n\n')
    }
    await page.render({ canvasContext: context, viewport }).promise
    const outcome = await runOcrImpl(canvas, onProgress)
    results.push(outcome.text)
  }
  return results.join('\n\n')
}

interface OcrOutcome {
  text: string
  languages: string
}

/**
 * OCR engine lifecycle. tesseract.js is created per recognition and always
 * terminated: a leaked worker keeps a WASM heap alive for the whole session.
 *
 * Language data and runtime are served from this app (`/tessdata`,
 * `/tesseract`), so recognition works offline; if the vendored runtime is
 * missing we fall back to tesseract.js's CDN defaults instead of failing.
 */
async function runOcr(
  source: Blob | HTMLCanvasElement,
  onProgress?: (message: string) => void,
): Promise<OcrOutcome> {
  const { createWorker } = await import('tesseract.js')
  const languages = 'chi_sim+eng'
  const localOptions = {
    workerPath: '/tesseract/worker.min.js',
    corePath: '/tesseract/tesseract-core-simd-lstm.wasm.js',
    langPath: '/tessdata',
    logger: (log: { status?: string; progress?: number }) => {
      if (log.status === 'recognizing text' && typeof log.progress === 'number') {
        onProgress?.(`OCR 识别中 ${Math.round(log.progress * 100)}%…`)
      }
    },
  }
  let worker: Awaited<ReturnType<typeof createWorker>> | null = null
  try {
    worker = await createWorker(languages, undefined, localOptions)
  } catch {
    onProgress?.('本地 OCR 运行时不可用，改用在线语言包…')
    worker = await createWorker(languages)
  }
  try {
    const result = await worker.recognize(source)
    return { text: result.data.text ?? '', languages }
  } finally {
    await worker.terminate()
  }
}

async function extractImage(
  file: File,
  onProgress?: (message: string) => void,
): Promise<ExtractedDocument> {
  onProgress?.('OCR 识别图片…')
  const outcome = await runOcrImpl(file, onProgress)
  const warnings: string[] = [`内容来自 OCR（${outcome.languages}），可能有识别误差`]
  const text = normalizeLines(outcome.text)
  if (!text) warnings.push('OCR 未从该图片中识别出文本')
  return { text: enforceLimit(text, warnings), kind: 'image', ocr: true, warnings }
}

async function extractPlainText(file: File): Promise<ExtractedDocument> {
  const text = normalizeLines(await readBlobAsText(file))
  const warnings: string[] = []
  return { text: enforceLimit(text, warnings), kind: 'text', ocr: false, warnings }
}

/**
 * Single entry point used by the resource dialog. Throws
 * `DocumentExtractionError` with a user-safe message for anything it cannot
 * read; every other failure is re-reported with the original message.
 */
export async function extractDocumentText(
  file: File,
  onProgress?: (message: string) => void,
): Promise<ExtractedDocument> {
  if (!isSupportedDocument(file.name)) {
    throw new DocumentExtractionError('UNSUPPORTED_TYPE', unsupportedDocumentMessage(file.name))
  }
  const extension = extensionOf(file.name)
  try {
    if (PDF_EXTENSIONS.includes(extension)) return await extractPdf(file, onProgress)
    if (DOCX_EXTENSIONS.includes(extension)) return await extractDocx(file)
    if (XLSX_EXTENSIONS.includes(extension)) return await extractXlsx(file)
    if (IMAGE_EXTENSIONS.includes(extension)) return await extractImage(file, onProgress)
    return await extractPlainText(file)
  } catch (error) {
    if (error instanceof DocumentExtractionError) throw error
    const detail = error instanceof Error ? error.message : String(error)
    throw new DocumentExtractionError(
      'EXTRACTION_FAILED',
      `${file.name} 解析失败：${detail}`,
    )
  }
}
