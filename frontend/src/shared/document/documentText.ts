// 文件名:documentText.ts
// 用途:资源附件的本地文档 → 纯文本抽取:PDF(文本层/OCR 兜底)、Word(.docx)、Excel(.xlsx)、图片(OCR)与纯文本,全程在浏览器内确定性完成,零模型调用、零网络上传。
/*
 * 面向资源附件的本地文档 → 纯文本抽取。
 *
 * 为什么本地:本项目的模型传输是纯文本聊天补全
 * (`ChatCompletionsProtocolAdapter` 写入 `content: <string>`),所以任何
 * PDF/Office 二进制都不会到达模型。文本必须存在于模型调用之前,而资源
 * 节点存储的正是它:`content.text`。因此抽取刻意保持确定性与低依赖
 * ——无解析模型、无服务器往返、无可变性输出。
 *
 * 路由:
 *  - `.pdf`                 → pdfjs 文本层(快,对数字 PDF 精确)
 *  - `.pdf` 无文本          → OCR 兜底(扫描页)
 *  - `.docx`                → OPC zip → `word/document.xml` 段落
 *  - `.xlsx` / `.xlsm`      → OPC zip → sharedStrings + 工作表单元格
 *  - 图片                    → OCR
 *  - `.txt` / `.md`         → 原始文本
 *
 * 一切都是"有上限且失败可见":抽不出文本的文档会被明确报告,而不是
 * 静默附加一个空资源。
 */
import JSZip from 'jszip'

/** 附加文本的上限。与浏览器侧的读取上限一致。 */
export const MAX_EXTRACTED_TEXT_BYTES = 256 * 1024

export type ExtractKind = 'text' | 'pdf' | 'docx' | 'xlsx' | 'image'

export interface ExtractedDocument {
  /** 写入 `content.text` 的纯文本。 */
  text: string
  kind: ExtractKind
  /** 文本来自 OCR 而非文本层时为 true。 */
  ocr: boolean
  /** PDF 页数 / 工作表名(有意义时)。 */
  pages?: number
  sheets?: string[]
  /** 用户可见的告警(截断、空页、OCR 语言等)。 */
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

/** OCR 需要点阵图;PDF 页先经 pdfjs 栅格化。 */
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

/*
 * Blob 读取辅助。
 *
 * 刻意基于 FileReader 而不是 `Blob.text()` / `.arrayBuffer()`:后者在
 * 单元测试运行的 jsdom 环境中缺失,而 FileReader 在应用运行的每个环境
 * 都可用。
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
  // 按字符切,再校验字节预算(中文每字符 3 字节)。
  let cut = Math.floor((text.length * MAX_EXTRACTED_TEXT_BYTES) / encoder.encode(text).length)
  while (cut > 0 && encoder.encode(text.slice(0, cut)).length > MAX_EXTRACTED_TEXT_BYTES) {
    cut = Math.floor(cut * 0.95)
  }
  warnings.push(`文档较长，已截断到约 ${Math.round(MAX_EXTRACTED_TEXT_BYTES / 1024)}KB`)
  return text.slice(0, cut)
}

/** pdf.js 的文本项对文本是 `{str}`;其它 item 类型不携带文本。 */
function pdfItemText(item: unknown): string {
  if (typeof item !== 'object' || item === null) return ''
  const str = (item as { str?: unknown }).str
  return typeof str === 'string' ? str : ''
}

/*
 * WordprocessingML → 文本。段落边界、段内换行与制表符是我们唯一尊重的
 * 结构,而且它们必须与文本 run 一起做词法切分:`<w:tab/>` / `<w:br/>`
 * 是 `<w:t>` 的兄弟节点,绝不在其内部,所以"先替换制表符再读 `<w:t>`"
 * 会静默丢掉它们。每个 run 的原文逐字保留。
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

/*
 * SpreadsheetML → 文本。只抽取值(不含公式/样式):目标是忠实、有界地
 * 读取工作表所显示的内容。每行变成一个制表符分隔的行,每个工作表有一行
 * 标题。
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
  // 自闭合单元格(`<c r="B2"/>`)必须先于成对形式匹配:否则 `[^>]*` 会
  // 吞掉 `/`,非贪婪的 body 一直跑到下一个 `</c>`,把两个单元格静默合并
  // 成一个。
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

/** 本模块实际使用的 pdfjs 切面(保持接缝窄)。 */
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

/*
 * pdfjs 懒加载:它会拉入约 1MB 的解析器 + worker,大多数会话根本用不到
 * (txt/md/docx/xlsx/OCR 路径不需要它),且该库只允许在浏览器中运行。
 */
async function loadPdfjsFromBundle(): Promise<PdfjsLike> {
  const pdfjs = await import('pdfjs-dist')
  const workerUrl = (await import('pdfjs-dist/build/pdf.worker.min.mjs?url')).default
  pdfjs.GlobalWorkerOptions.workerSrc = workerUrl
  return pdfjs as unknown as PdfjsLike
}

/*
 * 测试接缝。
 *
 * PDF 页循环与"无文本层 → OCR"的决策是本模块自己的逻辑,通过这些覆盖
 * 做单元测试;真正的 pdf.js 解析器与 tesseract worker 只在浏览器中运行,
 * 由浏览器内探针覆盖。生产路径绝不调用这里。
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
  // 完全没有文本层 → 几乎可以肯定是扫描件。回退到 OCR。
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

/*
 * OCR 引擎生命周期。tesseract.js 每次识别创建、并总是终止:泄漏的
 * worker 会让一个 WASM 堆存活整个会话。
 *
 * 语言数据与运行时由本应用提供(`/tessdata`、`/tesseract`),识别可离线
 * 工作;若本地运行时缺失,则回退到 tesseract.js 的 CDN 默认值而不是失败。
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

/*
 * 资源对话框使用的唯一入口。无法读取的文件抛出带用户安全消息的
 * `DocumentExtractionError`;其它任何失败都以原始消息重新上报。
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
