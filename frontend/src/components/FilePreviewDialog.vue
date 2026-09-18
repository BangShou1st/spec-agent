<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import ResourceBody from '@/components/ResourceBody.vue'
import { readerCanvasSize, readerImageSize, type Size } from '@/presentation/readerZoom'
import { resourceExtension, resourceKindOf } from '@/presentation/resourceKind'

/**
 * 文档阅读器。
 *
 * 按文件类型选择最合适的渲染方式，而不是一律丢进 <pre>：
 * - 图片 / PDF：直接用浏览器原生能力；
 * - Markdown：复用全局助手同一套富文本渲染（marked + DOMPurify），
 *   文档里的标题、列表、代码块、引用都能正确成型；
 * - 代码 / 数据（json、yaml、csv、脚本等）：等宽、不折行、可横向滚动；
 * - 纯文本：按正文排版，并把行宽限制在可读范围内（长行不再横跨整屏）。
 *
 * 弹窗必须 teleport 到 body：它渲染在 Vue Flow 节点内部，而节点带 CSS
 * transform，会成为 position:fixed 后代的包含块，导致弹窗被压在节点里。
 * 同一约定见 RelationProposalDialog。
 */
const props = defineProps<{
  open: boolean
  fileName: string | null
  dataUrl: string | null
  kind: 'pdf' | 'word' | 'excel' | 'image' | 'text'
  extractedText: string
}>()

const emit = defineEmits<{ close: [] }>()

const extension = computed(() => resourceExtension(props.fileName))

const hasOriginal = computed(() => props.dataUrl !== null)
const isImage = computed(() => props.kind === 'image' && props.dataUrl !== null)
const isPdf = computed(() => props.kind === 'pdf' && props.dataUrl !== null)
/** 呈现方式由共享规则决定，与 Skill 资源查看器同源（presentation/resourceKind）。 */
const resourceKind = computed(() => resourceKindOf(props.fileName))
const isMarkdown = computed(() => resourceKind.value === 'markdown')
const isCode = computed(() => resourceKind.value === 'code')
/** 二进制办公文档无法在浏览器里原样渲染，只能展示提取正文。 */
const isOfficeDocument = computed(() => props.kind === 'word' || props.kind === 'excel')

/** 头部标签：让「按类型适配」这件事对使用者可见。 */
const kindLabel = computed(() => {
  if (isImage.value) return '图片'
  if (isPdf.value) return 'PDF'
  if (isMarkdown.value) return 'Markdown'
  if (isCode.value) return extension.value.toUpperCase()
  if (props.kind === 'excel') return '表格'
  if (props.kind === 'word') return '文档'
  return '文本'
})

const textBody = computed(() => props.extractedText || '（无内容）')

/** 用像素尺寸还是用流式文本承载内容 —— 决定放大用哪种策略（见下方注释）。 */
const isCanvasContent = computed(() => isImage.value || isPdf.value)

/**
 * 阅读器内部缩放。
 *
 * 两种内容要分开处理，否则「放大」只会有一种正确：
 * - 流式内容（Markdown / 代码 / 纯文本）用 CSS `zoom`：字号、行高、折行宽度
 *   一起等比放大，文本仍然自然回流，正文区照常滚动 —— 这是阅读者想要的
 *   「字变大」，而不是把整块内容拉成一张位图。
 * - 画布内容（图片 / PDF）**由 JS 量出基准尺寸再乘倍数**（见 readerZoom.ts）。
 *   这里既不能用 `zoom`（它会把 `max-height: 100%` 一起放大，结果是图永远
 *   「刚好塞进窗口」，放大等于没发生），也不能只靠 `max-width: 100%`
 *   （那是上限不是基数，图小于窗口时会被自然尺寸卡住）。
 */
const ZOOM_LEVELS = [0.5, 0.67, 0.8, 1, 1.25, 1.5, 2, 2.5, 3]
const ZOOM_MIN = ZOOM_LEVELS[0]
const ZOOM_MAX = ZOOM_LEVELS[ZOOM_LEVELS.length - 1]

const zoom = ref(1)
/** 代码/数据的自动折行：默认不折行（保持缩进结构），需要时一键切换。 */
const wrap = ref(false)

const zoomPercent = computed(() => Math.round(zoom.value * 100))
const canZoomIn = computed(() => zoom.value < ZOOM_MAX)
const canZoomOut = computed(() => zoom.value > ZOOM_MIN)
const isZoomed = computed(() => Math.abs(zoom.value - 1) > 1e-6)

function zoomIn(): void {
  const next = ZOOM_LEVELS.find((level) => level > zoom.value + 1e-6)
  if (next !== undefined) zoom.value = next
}
function zoomOut(): void {
  const previous = [...ZOOM_LEVELS].reverse().find((level) => level < zoom.value - 1e-6)
  if (previous !== undefined) zoom.value = previous
}
function resetZoom(): void {
  zoom.value = 1
}

/** 画布内容的基准尺寸：正文区的内容盒，即「适应窗口」的目标。 */
const bodyEl = ref<HTMLElement | null>(null)
const viewportBox = ref<Size | null>(null)
const imageNatural = ref<Size | null>(null)

function measureViewportBox(): void {
  const el = bodyEl.value
  if (!el) return
  const style = getComputedStyle(el)
  const horizontal = parseFloat(style.paddingLeft) + parseFloat(style.paddingRight)
  const vertical = parseFloat(style.paddingTop) + parseFloat(style.paddingBottom)
  viewportBox.value = {
    w: Math.max(0, el.clientWidth - horizontal),
    h: Math.max(0, el.clientHeight - vertical),
  }
}

let resizeObserver: ResizeObserver | null = null
function stopMeasuring(): void {
  resizeObserver?.disconnect()
  resizeObserver = null
}

/** 窗口/弹窗尺寸变化时基准要跟着变，否则放大后「适应窗口」是假的。 */
function startMeasuring(): void {
  stopMeasuring()
  measureViewportBox()
  const el = bodyEl.value
  if (el && typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(measureViewportBox)
    resizeObserver.observe(el)
  }
}

function onImageLoad(event: Event): void {
  const img = event.target as HTMLImageElement
  if (img.naturalWidth > 0 && img.naturalHeight > 0) {
    imageNatural.value = { w: img.naturalWidth, h: img.naturalHeight }
    measureViewportBox()
  }
}

const hasMeasuredImage = computed(() => viewportBox.value !== null && imageNatural.value !== null)

const canvasImageStyle = computed(() => {
  const natural = imageNatural.value
  const box = viewportBox.value
  if (!natural || !box || box.w <= 0 || box.h <= 0) return undefined
  const size = readerImageSize(natural, box, zoom.value)
  return { width: `${size.w}px`, height: `${size.h}px` }
})

const canvasFrameStyle = computed(() => {
  const box = viewportBox.value
  if (!box || box.w <= 0 || box.h <= 0) return undefined
  const size = readerCanvasSize(box, zoom.value)
  return { width: `${size.w}px`, height: `${size.h}px` }
})

/** 画布内容的拖拽平移：放大后必须能把看漏的边角拽回视野里。 */
const panning = ref(false)
const panStart = { x: 0, y: 0, left: 0, top: 0 }

function onPointerDown(event: PointerEvent): void {
  const el = bodyEl.value
  if (!el || !isCanvasContent.value || zoom.value <= 1 || event.button !== 0) return
  panning.value = true
  panStart.x = event.clientX
  panStart.y = event.clientY
  panStart.left = el.scrollLeft
  panStart.top = el.scrollTop
  el.setPointerCapture?.(event.pointerId)
}
function onPointerMove(event: PointerEvent): void {
  const el = bodyEl.value
  if (!panning.value || !el) return
  el.scrollLeft = panStart.left - (event.clientX - panStart.x)
  el.scrollTop = panStart.top - (event.clientY - panStart.y)
}
function onPointerUp(event: PointerEvent): void {
  if (!panning.value) return
  panning.value = false
  bodyEl.value?.releasePointerCapture?.(event.pointerId)
}

/** 双击图片在 适应窗口 / 200% 之间来回，是最省事的「放大看细节」入口。 */
function toggleZoom(): void {
  if (!isCanvasContent.value) return
  zoom.value = zoom.value <= 1 ? 2 : 1
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    emit('close')
    return
  }
  if (!(event.ctrlKey || event.metaKey)) return
  if (event.key === '=' || event.key === '+') {
    event.preventDefault()
    zoomIn()
  } else if (event.key === '-' || event.key === '_') {
    event.preventDefault()
    zoomOut()
  } else if (event.key === '0') {
    event.preventDefault()
    resetZoom()
  }
}

watch(() => props.open, (open) => {
  if (open) {
    // 换一个文件就从适应窗口开始，不继承上一个文件的缩放。
    zoom.value = 1
    wrap.value = false
    imageNatural.value = null
    window.addEventListener('keydown', onKeydown)
    void nextTick(startMeasuring)
  } else {
    window.removeEventListener('keydown', onKeydown)
    stopMeasuring()
    viewportBox.value = null
    imageNatural.value = null
  }
}, { immediate: true })

onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown)
  stopMeasuring()
})
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="file-preview-backdrop" data-test="file-preview-dialog" @click.self="emit('close')">
      <div class="file-preview" role="dialog" aria-modal="true" :aria-label="`查看文件：${fileName ?? ''}`">
        <header class="file-preview__header">
          <span class="file-preview__kind" data-test="file-preview-kind">{{ kindLabel }}</span>
          <span class="file-preview__title" data-test="file-preview-name">{{ fileName ?? '文件' }}</span>

          <div class="file-preview__tools" role="group" aria-label="阅读工具" data-test="file-preview-tools">
            <button
              class="file-preview__tool"
              type="button"
              title="自动折行（长行走在容器内）"
              :aria-pressed="wrap ? 'true' : 'false'"
              :disabled="!isCode"
              data-test="file-preview-wrap"
              @click="wrap = !wrap"
            >{{ wrap ? '已折行' : '折行' }}</button>

            <button
              class="file-preview__tool"
              type="button"
              aria-label="缩小"
              title="缩小（Ctrl+−）"
              :disabled="!canZoomOut"
              data-test="file-preview-zoom-out"
              @click="zoomOut"
            >−</button>
            <button
              class="file-preview__tool file-preview__tool--level"
              type="button"
              :title="isZoomed ? '回到适应窗口（Ctrl+0）' : '适应窗口'"
              data-test="file-preview-zoom-reset"
              @click="resetZoom"
            ><span data-test="file-preview-zoom-level">{{ zoomPercent }}%</span></button>
            <button
              class="file-preview__tool"
              type="button"
              aria-label="放大"
              title="放大（Ctrl++）"
              :disabled="!canZoomIn"
              data-test="file-preview-zoom-in"
              @click="zoomIn"
            >+</button>
          </div>

          <button class="btn" type="button" data-test="file-preview-close" @click="emit('close')">关闭</button>
        </header>

        <div
          ref="bodyEl"
          class="file-preview__body"
          :data-zoom="zoom"
          :class="{
            'file-preview__body--narrow': !isCode && !isCanvasContent,
            'file-preview__body--grabbable': isCanvasContent && zoom > 1,
            'file-preview__body--grabbing': panning,
          }"
          @pointerdown="onPointerDown"
          @pointermove="onPointerMove"
          @pointerup="onPointerUp"
          @pointercancel="onPointerUp"
        >
          <!-- 画布内容：基准尺寸由 JS 量出（适应窗口但不放大），再乘缩放倍数；
               超出正文区后由滚动 / 拖拽查看。 -->
          <div v-if="isCanvasContent" class="file-preview__canvas" @dblclick="toggleZoom">
            <img
              v-if="isImage"
              class="file-preview__image"
              :class="{ 'file-preview__image--measured': hasMeasuredImage }"
              :style="canvasImageStyle"
              :src="dataUrl ?? ''"
              :alt="fileName ?? '图片原件'"
              data-test="file-preview-original"
              @load="onImageLoad"
            />
            <iframe
              v-else
              class="file-preview__frame"
              :style="canvasFrameStyle"
              :src="dataUrl ?? ''"
              title="PDF 原件预览"
              data-test="file-preview-original"
            ></iframe>
          </div>

          <!-- 流式内容：`zoom` 让字号与排版一起等比放大，文本仍然自然回流。 -->
          <div v-else class="file-preview__flow" :style="{ zoom: String(zoom) }">
            <p
              v-if="!hasOriginal && !isMarkdown"
              class="file-preview__notice meta-text"
              data-test="file-preview-missing"
            >
              {{ isOfficeDocument
                ? '该文件无法在浏览器内直接渲染，以下为解析出的正文'
                : '该文件添加于原件保存功能上线前，仅保留提取的文本内容' }}
            </p>
            <!-- 正文渲染与 Skill 资源查看器共用 ResourceBody：Markdown 走富文本，
                 代码/数据等宽不折行，`data-test` 由根元素承接。 -->
            <ResourceBody
              :path="fileName"
              :content="textBody"
              :wrap="wrap"
              :data-test="hasOriginal ? 'file-preview-original' : 'file-preview-extracted'"
            />
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.file-preview-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(15, 20, 30, 0.55);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px;
  /* 最高层：必须盖过普通弹窗（.ui-veil 是 40）。 */
  z-index: 90;
}
.file-preview {
  background: var(--color-surface);
  border-radius: var(--radius-lg);
  width: 100%;
  height: 100%;
  max-width: 1200px;
  display: flex;
  flex-direction: column;
  box-shadow: 0 16px 48px rgba(15, 20, 30, 0.35);
  overflow: hidden;
}
.file-preview__header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--color-border);
  flex: none;
}
.file-preview__kind {
  flex: none;
  padding: 2px 8px;
  border: 1px solid var(--color-border);
  border-radius: 999px;
  background: var(--color-surface-subtle);
  color: var(--color-text-secondary);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.02em;
}
.file-preview__title {
  flex: 1;
  min-width: 0;
  font-weight: 650;
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 阅读工具组：按宽度自动让位，窄窗口下不会把标题挤没。 */
.file-preview__tools {
  flex: none;
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 2px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-surface-subtle);
}
.file-preview__tool {
  min-width: 28px;
  height: 24px;
  padding: 0 6px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--color-text-secondary);
  font-size: 12px;
  font-weight: 650;
  line-height: 1;
  cursor: pointer;
}
.file-preview__tool:hover:not(:disabled) {
  background: var(--color-surface);
  color: var(--color-text);
}
.file-preview__tool:disabled {
  opacity: 0.4;
  cursor: default;
}
.file-preview__tool[aria-pressed='true'] {
  background: var(--color-surface);
  color: var(--color-accent-strong);
}
.file-preview__tool--level {
  min-width: 52px;
  font-variant-numeric: tabular-nums;
}
.file-preview__body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 20px 24px;
}
/* 画布内容放大后可拖拽平移。 */
.file-preview__body--grabbable {
  cursor: grab;
}
.file-preview__body--grabbing {
  cursor: grabbing;
}
/* 正文类文档限制行宽：整屏宽的一行中文极难回行阅读。
   上限要同时受视口约束 —— `ch` 是随字号变的单位，而 `zoom` 会把它一起放大，
   只写 78ch 的话放到 300% 会撑出横向滚动条，正文反而读不了。 */
.file-preview__body--narrow .file-preview__flow > * {
  width: 100%;
  max-width: min(78ch, 100%);
  margin: 0 auto;
}
/* 画布内容：自身就是「适应窗口」的尺寸，放大靠子元素撑出去。
   用 flex + `margin: auto` 而不是 justify-content: center —— 内容比容器大时
   auto margin 会退化为 0，元素贴住起始边，滚动才够得到左上角。 */
.file-preview__canvas {
  display: flex;
  min-height: 100%;
}
.file-preview__image {
  margin: auto;
  object-fit: contain;
  /* 量到自然尺寸之前先按窗口收敛，避免一帧内溢出。 */
  max-width: 100%;
  max-height: 100%;
}
.file-preview__image--measured {
  max-width: none;
  max-height: none;
}
.file-preview__frame {
  margin: auto;
  border: 0;
  background: var(--color-surface-subtle);
}
/* 正文排版（Markdown / 代码 / 纯文本）由 ResourceBody 统一定义，这里不再重复。 */
.file-preview__notice {
  margin: 0 0 10px;
  color: var(--color-warn);
}
</style>
