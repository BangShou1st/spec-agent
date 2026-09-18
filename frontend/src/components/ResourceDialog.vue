<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  DocumentExtractionError,
  extractDocumentText,
  extensionOf,
  type ExtractKind,
} from '@/util/documentText'

/**
 * 添加资源节点对话框。资源是能力的上下文来源（AI 通过能力读取有界摘录），
 * 不是已确认的需求事实。
 *
 * 三种类型：
 * - 文本：直接粘贴内容；
 * - 链接：URL（可附一段摘录）；
 * - 本地文件：在浏览器内把文档抽成正文放进 content.text。
 *
 * 文件抽取（`@/util/documentText`）是本地的、确定性的：
 * PDF 走文本层（无文本层时自动退回 OCR），.docx / .xlsx 直接解 OPC 包，
 * 图片走 OCR。全程**不调用任何解析模型**，也不上传文件 —— 只有抽出的文本
 * 会随资源节点一起存进 content.text。
 */
const props = defineProps<{
  open: boolean
  pending: boolean
  /**
   * 已不再影响资源创建：资源现在总是先独立存在（不属于任何路线）。
   * 保留该 prop 以免调用方签名漂移，但库里不再用它渲染文案。
   */
  routeEmpty?: boolean
}>()

const emit = defineEmits<{
  close: []
  submit: [subtype: 'TEXT' | 'URL' | 'FILE', content: Record<string, unknown>]
}>()

const subtype = ref<'TEXT' | 'URL' | 'FILE'>('TEXT')
const text = ref('')
const url = ref('')
const fileName = ref<string | null>(null)
/** 原件以 data URL 随节点保存（本地浏览器内转换，无网络上传），供查看弹窗还原原件。 */
const fileDataUrl = ref<string | null>(null)
const fileSize = ref<number | null>(null)
const fileError = ref<string | null>(null)
const reading = ref(false)
const readProgress = ref<string | null>(null)
const fileKind = ref<ExtractKind | null>(null)
const fileOcr = ref(false)
const fileMeta = ref<string | null>(null)
const fileWarnings = ref<string[]>([])

/** 支持直接解析的格式；未列出的类型一律明确拒绝，不做静默降级。 */
const ACCEPTED_FILE_EXTENSIONS = [
  '.pdf', '.docx', '.xlsx', '.xlsm', '.txt', '.md', '.markdown', '.csv',
  '.png', '.jpg', '.jpeg', '.webp', '.bmp',
]
const ACCEPT_FILE_TYPES = ACCEPTED_FILE_EXTENSIONS.join(',')

watch(() => props.open, (open) => {
  if (open) {
    subtype.value = 'TEXT'
    text.value = ''
    url.value = ''
    fileName.value = null
    fileDataUrl.value = null
    fileSize.value = null
    fileError.value = null
    reading.value = false
    readProgress.value = null
    fileKind.value = null
    fileOcr.value = false
    fileMeta.value = null
    fileWarnings.value = []
  }
}, { immediate: true })

const canSubmit = computed(() => {
  if (props.pending || reading.value) return false
  if (subtype.value === 'TEXT') return text.value.trim().length > 0
  if (subtype.value === 'URL') return url.value.trim().length > 0
  return fileName.value !== null && text.value.trim().length > 0
})

const fileKindLabel = computed<string | null>(() => {
  if (!fileKind.value) return null
  const ocrSuffix = fileOcr.value ? ' · OCR' : ''
  if (fileKind.value === 'pdf') return `PDF 文本层${ocrSuffix}`
  if (fileKind.value === 'docx') return 'Word 正文'
  if (fileKind.value === 'xlsx') return 'Excel 单元格'
  if (fileKind.value === 'image') return '图片 OCR'
  return '纯文本'
})

function resetFileState(): void {
  fileName.value = null
  fileDataUrl.value = null
  fileSize.value = null
  fileKind.value = null
  fileOcr.value = false
  fileMeta.value = null
  fileWarnings.value = []
}

/** 浏览器内把文件转成 data URL，仅本地读取，无网络请求。 */
function readAsDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result))
    reader.onerror = () => reject(new Error('read failed'))
    reader.readAsDataURL(file)
  })
}

async function onFileChosen(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  fileError.value = null
  if (!file) return
  const extension = extensionOf(file.name)
  if (!ACCEPTED_FILE_EXTENSIONS.includes(extension)) {
    fileError.value = `暂不支持 ${extension || '该'} 格式。可解析：${ACCEPTED_FILE_EXTENSIONS.join(' / ')}`
    resetFileState()
    input.value = ''
    return
  }
  reading.value = true
  readProgress.value = `正在读取 ${file.name}…`
  text.value = ''
  resetFileState()
  try {
    const result = await extractDocumentText(file, (message) => {
      readProgress.value = message
    })
    text.value = result.text
    fileName.value = file.name
    fileDataUrl.value = await readAsDataUrl(file)
    fileSize.value = file.size
    fileKind.value = result.kind
    fileOcr.value = result.ocr
    fileWarnings.value = result.warnings
    const meta: string[] = []
    if (result.pages) meta.push(`${result.pages} 页`)
    if (result.sheets?.length) meta.push(`工作表：${result.sheets.join('、')}`)
    meta.push(`${Math.round(new Blob([result.text]).size / 1024)}KB 文本`)
    fileMeta.value = meta.join(' · ')
    if (!result.text) {
      fileError.value = '未从该文件中提取到文本，请改用其它格式或直接粘贴内容'
      resetFileState()
    }
  } catch (error) {
    fileError.value = error instanceof DocumentExtractionError
      ? error.message
      : '解析文件失败，请重试或直接粘贴内容'
    resetFileState()
  } finally {
    reading.value = false
    readProgress.value = null
  }
}

function submit(): void {
  if (!canSubmit.value) return
  const content: Record<string, unknown> = {}
  if (subtype.value === 'TEXT') {
    content.text = text.value.trim()
  } else if (subtype.value === 'URL') {
    if (url.value.trim()) content.url = url.value.trim()
    if (text.value.trim()) content.text = text.value.trim()
  } else {
    content.fileName = fileName.value
    content.text = text.value.trim()
    // 原件（data URL）与大小随节点保存：点击卡片可再次查看原件。
    if (fileDataUrl.value) content.fileDataUrl = fileDataUrl.value
    if (fileSize.value !== null) content.fileSize = fileSize.value
    if (fileKind.value) content.extractKind = fileKind.value
    if (fileOcr.value) content.extractedByOcr = true
  }
  emit('submit', subtype.value, content)
}
</script>

<template>
  <div v-if="open" class="dialog-backdrop" data-test="resource-dialog">
    <div class="dialog" role="dialog" aria-modal="true" aria-label="添加资源">
      <h3 style="margin-top: 0">添加资源</h3>
      <p class="muted" style="margin-top: 0">
        资源先作为<strong>独立节点</strong>存在，不挂到任何路线上；之后在画布上把它的连线拖到某条路线的末端节点即可接入。
        AI 只有接入后才读得到它的有界摘录（带来源引用），资源本身不是已确认的需求
      </p>

      <label class="secondary field-label">
        <span>资源类型</span>
        <select v-model="subtype" class="answer-input" data-test="resource-subtype">
          <option value="TEXT">文本</option>
          <option value="URL">链接</option>
          <option value="FILE">本地文件（PDF / Word / Excel / 图片 / 文本）</option>
        </select>
      </label>

      <label v-if="subtype === 'URL'" class="secondary field-label">
        <span>链接地址</span>
        <input v-model="url" class="answer-input" data-test="resource-url" placeholder="https://…" />
      </label>

      <label v-if="subtype === 'FILE'" class="secondary field-label">
        <span>选择文件</span>
        <input
          type="file"
          class="answer-input"
          data-test="resource-file"
          :accept="ACCEPT_FILE_TYPES"
          :disabled="pending || reading"
          @change="onFileChosen"
        />
      </label>
      <p v-if="subtype === 'FILE'" class="meta-text">
        在浏览器内本地解析：PDF 取文本层（无文本层自动退回 OCR）、.docx/.xlsx 解正文、
        图片走 OCR；不调用解析模型、不承诺上传原始文件
      </p>
      <p v-if="reading && readProgress" class="meta-text" data-test="resource-file-progress">
        {{ readProgress }}
      </p>
      <p v-if="subtype === 'FILE' && fileName" class="meta-text" data-test="resource-file-name">
        已读取：{{ fileName }}<template v-if="fileKindLabel">（{{ fileKindLabel }}<template v-if="fileMeta">，{{ fileMeta }}</template>）</template>
      </p>
      <ul v-if="fileWarnings.length" class="resource-dialog__warnings" data-test="resource-file-warnings">
        <li v-for="warning in fileWarnings" :key="warning">{{ warning }}</li>
      </ul>
      <p v-if="fileError" class="resource-dialog__error" data-test="resource-file-error">
        {{ fileError }}
      </p>

      <label
        v-if="subtype !== 'FILE' || fileName"
        class="secondary field-label"
      >
        <span>{{ subtype === 'TEXT' ? '资源内容' : (subtype === 'FILE' ? '内容预览（可编辑）' : '内容摘录（可选）') }}</span>
        <textarea
          v-model="text"
          class="answer-input"
          data-test="resource-text"
          rows="5"
          placeholder="粘贴文本内容…"
        ></textarea>
      </label>

      <div class="dialog-actions">
        <button class="btn btn-primary" type="button" data-test="resource-submit" :disabled="!canSubmit" @click="submit">
          {{ pending ? '正在添加…' : (reading ? '正在解析…' : '添加资源') }}
        </button>
        <button class="btn" type="button" data-test="resource-cancel" :disabled="pending" @click="emit('close')">取消</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dialog-backdrop { position: fixed; inset: 0; background: rgba(15, 20, 30, 0.45); display: flex; align-items: flex-start; justify-content: center; padding: 80px 16px; z-index: 40; }
.dialog { background: var(--color-surface); border-radius: var(--radius); padding: 18px; width: 100%; max-width: 520px; box-shadow: 0 12px 32px rgba(15, 20, 30, 0.25); }
.field-label { display: block; margin-top: 12px; font-size: 13px; }
.field-label input, .field-label textarea, .field-label select { display: block; margin-top: 4px; width: 100%; box-sizing: border-box; }
.dialog-actions { margin-top: 14px; }
.resource-dialog__error { margin: 6px 0 0; font-size: 12px; color: var(--color-danger); }
.resource-dialog__warnings { margin: 6px 0 0; padding-left: 18px; font-size: 12px; color: var(--color-warn); }
</style>
