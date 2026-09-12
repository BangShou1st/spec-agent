<script setup lang="ts">
import { computed } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
const props = defineProps<{ content: string }>()
const ALLOWED_TAGS = ['p','h2','h3','strong','em','ul','ol','li','blockquote','code','pre','a','br','hr']
function renderMarkdown(src: string): string {
  if (!src) return ''
  try {
    let downgraded = src.replace(/^#\s+(.*)$/gm, '## $1')
    const raw = marked.parse(downgraded, { breaks: true, gfm: true }) as string
    const clean = DOMPurify.sanitize(raw, {
      ALLOWED_TAGS,
      ALLOWED_ATTR: ['href','title','rel','target'],
      ALLOW_DATA_ATTR: false,
      ALLOWED_URI_REGEXP: /^(https?:|mailto:)[^\s]*$/i,
    })
    return clean
  } catch { return '' }
}
const html = computed(() => {
  const out = renderMarkdown(props.content)
  if (out) return out
  const div = document.createElement('div')
  div.textContent = props.content
  return '<p>' + div.innerHTML + '</p>'
})
</script>
<template>
  <div class="rich-text" data-test="ga-rich-text" v-html="html" />
</template>
<style scoped>
.rich-text { font-size: 13.5px; line-height: 1.65; color: var(--color-text); overflow-wrap: anywhere; min-width: 0; }
.rich-text :deep(h2), .rich-text :deep(h3) { margin: 10px 0 6px; font-weight: 650; line-height: 1.4; }
.rich-text :deep(h2) { font-size: 15px; }
.rich-text :deep(h3) { font-size: 14px; }
.rich-text :deep(p) { margin: 0 0 8px; }
.rich-text :deep(ul), .rich-text :deep(ol) { margin: 0 0 8px; padding-left: 20px; }
.rich-text :deep(li) { margin: 2px 0; }
.rich-text :deep(blockquote) { margin: 0 0 8px; padding: 6px 10px; border: 1px solid var(--color-border); color: var(--color-text-secondary); background: var(--color-surface-subtle); border-radius: 8px; }
.rich-text :deep(code) { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12.5px; background: var(--color-surface-subtle); border: 1px solid var(--color-border); border-radius: 6px; padding: 0 4px; }
.rich-text :deep(pre) { margin: 0 0 8px; padding: 10px 12px; background: var(--color-surface-subtle); border: 1px solid var(--color-border); border-radius: 8px; overflow-x: auto; }
.rich-text :deep(pre code) { background: transparent; border: 0; padding: 0; }
.rich-text :deep(a) { color: var(--color-accent-strong); text-decoration: underline; text-underline-offset: 2px; overflow-wrap: anywhere; }
</style>
