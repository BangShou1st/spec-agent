import { describe, expect, it, vi } from 'vitest'
import JSZip from 'jszip'
import {
  configureDocumentExtractorsForTests,
  DocumentExtractionError,
  docxXmlToText,
  extensionOf,
  extractDocumentText,
  isSupportedDocument,
  MAX_EXTRACTED_TEXT_BYTES,
  unsupportedDocumentMessage,
  xlsxSheetPartToText,
  xlsxSheetsFromWorkbook,
  type PdfDocumentLike,
} from '@/util/documentText'

const XML_HEADER = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'

async function zipToFile(name: string, entries: Record<string, string>): Promise<File> {
  const zip = new JSZip()
  for (const [path, body] of Object.entries(entries)) zip.file(path, body)
  const blob = await zip.generateAsync({ type: 'blob' })
  return new File([blob], name)
}

function docxSource(paragraphs: string[]): string {
  return [
    XML_HEADER,
    '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>',
    ...paragraphs.map((paragraph) => `<w:p>${paragraph}</w:p>`),
    '</w:body></w:document>',
  ].join('')
}

function xlsxSource(cells: { ref: string; type?: string; value: string }[][], sharedStrings: string[]): {
  workbook: string
  rels: string
  sheet: string
  shared: string
} {
  const rows = cells.map((row, rowIndex) => {
    const cellsXml = row.map((cell) => {
      const type = cell.type ? ` t="${cell.type}"` : ''
      return `<c r="${cell.ref}"${type}><v>${cell.value}</v></c>`
    }).join('')
    return `<row r="${rowIndex + 1}">${cellsXml}</row>`
  }).join('')
  const shared = sharedStrings.length === 0 ? '' : [
    XML_HEADER,
    `<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="${sharedStrings.length}">`,
    ...sharedStrings.map((entry) => `<si><t>${entry}</t></si>`),
    '</sst>',
  ].join('')
  return {
    workbook: [
      XML_HEADER,
      '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"',
      ' xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">',
      '<sheets><sheet name="需求清单" sheetId="1" r:id="rId1"/><sheet name="预算" sheetId="2" r:id="rId2"/></sheets>',
      '</workbook>',
    ].join(''),
    rels: [
      XML_HEADER,
      '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">',
      '<Relationship Id="rId1" Type="x" Target="worksheets/sheet1.xml"/>',
      '<Relationship Id="rId2" Type="x" Target="worksheets/sheet2.xml"/>',
      '</Relationships>',
    ].join(''),
    sheet: `${XML_HEADER}<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>${rows}</sheetData></worksheet>`,
    shared,
  }
}

describe('文档类型识别', () => {
  it('按扩展名分流并接受本次新增的格式', () => {
    for (const name of ['a.pdf', 'a.docx', 'a.xlsx', 'a.xlsm', 'a.txt', 'a.md', 'a.png', 'a.JPG']) {
      expect(isSupportedDocument(name), name).toBe(true)
    }
    expect(isSupportedDocument('a.ppt')).toBe(false)
    expect(isSupportedDocument('a.doc')).toBe(false)
    expect(extensionOf('报告.PDF')).toBe('.pdf')
  })

  it('拒绝时给出可支持格式清单，不做静默降级', async () => {
    const file = new File(['x'], 'slides.ppt')
    await expect(extractDocumentText(file)).rejects.toThrowError(DocumentExtractionError)
    expect(unsupportedDocumentMessage('slides.ppt')).toContain('.docx')
  })
})

describe('Word (.docx) 正文抽取', () => {
  it('把段落拼成换行文本，保留 run 顺序、制表符与实体', () => {
    const xml = docxSource([
      '<w:r><w:t>需求</w:t></w:r><w:r><w:t xml:space="preserve"> 综述</w:t></w:r>',
      '<w:r><w:t>第一项</w:t><w:tab/><w:t>负责人</w:t></w:r>',
      '<w:r><w:t>A &amp; B &lt;草案&gt;</w:t></w:r>',
      '<w:r><w:t></w:t></w:r>',
    ])
    expect(docxXmlToText(xml)).toBe('需求 综述\n第一项\t负责人\nA & B <草案>')
  })

  it('从真实 OPC 包里读出正文（zip → word/document.xml）', async () => {
    const file = await zipToFile('需求说明.docx', {
      '[Content_Types].xml': `${XML_HEADER}<Types/>`,
      'word/document.xml': docxSource([
        '<w:r><w:t>记账应用需要支持多人协作。</w:t></w:r>',
        '<w:r><w:t>导出格式优先 CSV</w:t></w:r>',
      ]),
    })
    const result = await extractDocumentText(file)
    expect(result.kind).toBe('docx')
    expect(result.ocr).toBe(false)
    expect(result.text).toBe('记账应用需要支持多人协作。\n导出格式优先 CSV')
    expect(result.warnings).toEqual([])
  })

  it('没有正文时明确报空，而不是静默附加空资源', async () => {
    const file = await zipToFile('空.docx', { 'word/document.xml': docxSource([]) })
    const result = await extractDocumentText(file)
    expect(result.text).toBe('')
    expect(result.warnings.join()).toContain('没有可提取的正文文本')
  })

  it('缺 word/document.xml 时报错', async () => {
    const file = await zipToFile('坏.docx', { 'word/styles.xml': '<w:styles/>' })
    await expect(extractDocumentText(file)).rejects.toThrowError(/无法读取 Word 正文/)
  })
})

describe('Excel (.xlsx) 单元格抽取', () => {
  it('解析 workbook 关系并保留工作表顺序与名称', () => {
    const { workbook, rels } = xlsxSource([], [])
    const sheets = xlsxSheetsFromWorkbook(workbook, rels)
    expect(sheets).toEqual([
      { name: '需求清单', path: 'xl/worksheets/sheet1.xml' },
      { name: '预算', path: 'xl/worksheets/sheet2.xml' },
    ])
  })

  it('共享字符串、内联字符串、数值、空单元格都取到正确位置', () => {
    const sheet = [
      XML_HEADER,
      '<worksheet><sheetData>',
      '<row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1"><v>120</v></c></row>',
      '<row r="2"><c r="A2" t="inlineStr"><is><t>研发</t></is></c><c r="B2"/><c r="C2" t="str"><v>合计</v></c></row>',
      '<row r="3"><c r="A3"/><c r="B3"/></row>',
      '</sheetData></worksheet>',
    ].join('')
    expect(xlsxSheetPartToText(sheet, ['阶段', '负责人'])).toBe('阶段\t负责人\t120\n研发\t\t合计')
  })

  it('从真实 OPC 包里读出所有工作表', async () => {
    const source = xlsxSource(
      [
        [{ ref: 'A1', type: 's', value: '0' }, { ref: 'B1', type: 's', value: '1' }],
        [{ ref: 'A2', type: 's', value: '2' }, { ref: 'B2', value: '3' }],
      ],
      ['条目', '金额', '服务器'],
    )
    const file = await zipToFile('预算.xlsx', {
      'xl/workbook.xml': source.workbook,
      'xl/_rels/workbook.xml.rels': source.rels,
      'xl/sharedStrings.xml': source.shared,
      'xl/worksheets/sheet1.xml': source.sheet,
      'xl/worksheets/sheet2.xml': source.sheet,
    })
    const result = await extractDocumentText(file)
    expect(result.kind).toBe('xlsx')
    expect(result.sheets).toEqual(['需求清单', '预算'])
    expect(result.text).toContain('# 需求清单')
    expect(result.text).toContain('条目\t金额')
    expect(result.text).toContain('# 预算')
  })

  it('缺 workbook 时报错', async () => {
    const file = await zipToFile('坏.xlsx', { 'xl/sharedStrings.xml': '<sst/>' })
    await expect(extractDocumentText(file)).rejects.toThrowError(/无法读取 Excel 工作簿/)
  })
})

describe('体积上限', () => {
  it('超长文本被截断并给出提示，字节数不超过上限', async () => {
    const line = '需求条目需要确认边界与优先级。\n'
    const file = new File([line.repeat(20000)], 'long.txt')
    const result = await extractDocumentText(file)
    expect(result.warnings.join()).toContain('已截断')
    expect(new Blob([result.text]).size).toBeLessThanOrEqual(MAX_EXTRACTED_TEXT_BYTES)
  })

  it('正常长度的文本不截断', async () => {
    const file = new File(['短内容'], 'short.md')
    const result = await extractDocumentText(file)
    expect(result.kind).toBe('text')
    expect(result.warnings).toEqual([])
  })
})

/**
 * PDF 分支：真实 pdf.js 只能在浏览器里跑（Node 下 fake worker 需要 legacy
 * 构建），所以这里注入一个假解析器，专测本模块自己的逻辑 ——
 * 逐页取文本、空页提示、无文本层时退回 OCR。
 */
function pdfWithPages(pages: (string | null)[]): { loadPdfjs: () => Promise<unknown> } {
  const document: PdfDocumentLike = {
    numPages: pages.length,
    getPage: async (pageNumber: number) => ({
      getTextContent: async () => ({
        items: pages[pageNumber - 1] === null
          ? []
          : (pages[pageNumber - 1] as string).split(' ').map((str) => ({ str })),
      }),
      getViewport: () => ({ width: 100, height: 80 }),
      render: () => ({ promise: Promise.resolve() }),
    }),
  }
  return {
    loadPdfjs: async () => ({
      getDocument: () => ({ promise: Promise.resolve(document) }),
      GlobalWorkerOptions: { workerSrc: '' },
    }),
  }
}

describe('PDF 文本层与扫描件回退', () => {
  /** jsdom 没有 2D 上下文实现：只补"能拿到 context"这一件事。 */
  function stubCanvasContext(value: unknown): () => void {
    const spy = vi.spyOn(
      HTMLCanvasElement.prototype as unknown as { getContext: () => unknown },
      'getContext',
    ).mockReturnValue(value)
    return () => spy.mockRestore()
  }

  const restore = () => configureDocumentExtractorsForTests({
    loadPdfjs: async () => {
      throw new Error('pdfjs 不应在非 PDF 用例中被加载')
    },
  })

  it('逐页抽取文本层并给出页数', async () => {
    const { loadPdfjs } = pdfWithPages(['Ledger app', 'Export CSV'])
    configureDocumentExtractorsForTests({ loadPdfjs: loadPdfjs as never })
    const result = await extractDocumentText(new File(['%PDF'], 'spec.pdf'))
    expect(result.kind).toBe('pdf')
    expect(result.ocr).toBe(false)
    expect(result.pages).toBe(2)
    expect(result.text).toBe('Ledger app\n\nExport CSV')
    restore()
  })

  it('部分页面没有文本层时给出页码提示', async () => {
    const { loadPdfjs } = pdfWithPages(['Page one', null])
    configureDocumentExtractorsForTests({ loadPdfjs: loadPdfjs as never })
    const result = await extractDocumentText(new File(['%PDF'], 'spec.pdf'))
    expect(result.ocr).toBe(false)
    expect(result.text).toBe('Page one')
    expect(result.warnings.join()).toContain('第 2 页没有文本层')
    restore()
  })

  it('完全没有文本层 = 扫描件：自动退回 OCR', async () => {
    const { loadPdfjs } = pdfWithPages([null, null])
    const restoreCanvas = stubCanvasContext({})
    const ocr = vi.fn(async () => ({ text: '扫描出来的正文', languages: 'chi_sim+eng' }))
    configureDocumentExtractorsForTests({ loadPdfjs: loadPdfjs as never, runOcr: ocr })
    const result = await extractDocumentText(new File(['%PDF'], 'scan.pdf'))
    expect(result.ocr).toBe(true)
    expect(result.pages).toBe(2)
    expect(result.text).toContain('扫描出来的正文')
    // 每一页都送进 OCR（2 页 → 2 次调用）。
    expect(ocr).toHaveBeenCalledTimes(2)
    restoreCanvas()
    restore()
  })

  it('拿不到 canvas 上下文时不静默：提示已跳过 OCR', async () => {
    const { loadPdfjs } = pdfWithPages([null])
    const restoreCanvas = stubCanvasContext(null)
    configureDocumentExtractorsForTests({
      loadPdfjs: loadPdfjs as never,
      runOcr: async () => ({ text: '不应被调用', languages: 'chi_sim+eng' }),
    })
    const result = await extractDocumentText(new File(['%PDF'], 'scan.pdf'))
    expect(result.text).toBe('')
    expect(result.warnings.join()).toContain('无法渲染 PDF 页面')
    restoreCanvas()
    restore()
  })

  it('OCR 也没识别出内容时明确报空', async () => {
    const { loadPdfjs } = pdfWithPages([null])
    const restoreCanvas = stubCanvasContext({})
    configureDocumentExtractorsForTests({
      loadPdfjs: loadPdfjs as never,
      runOcr: async () => ({ text: '   ', languages: 'chi_sim+eng' }),
    })
    const result = await extractDocumentText(new File(['%PDF'], 'scan.pdf'))
    expect(result.text).toBe('')
    expect(result.warnings.join()).toContain('OCR 未能从该 PDF 中识别出文本')
    restoreCanvas()
    restore()
  })
})

describe('图片走 OCR', () => {
  it('识别结果带上 OCR 来源提示与语言', async () => {
    configureDocumentExtractorsForTests({
      runOcr: async () => ({ text: '收据金额 128 元', languages: 'chi_sim+eng' }),
    })
    const result = await extractDocumentText(new File(['x'], 'receipt.png'))
    expect(result.kind).toBe('image')
    expect(result.ocr).toBe(true)
    expect(result.text).toBe('收据金额 128 元')
    expect(result.warnings.join()).toContain('内容来自 OCR')
  })
})
