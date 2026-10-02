<script setup lang="ts">
import { computed } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import type { GaResourceRef } from '../state/globalAssistantStore'
import { resolveWebCitations } from '../presentation/webSources'
const props = defineProps<{ content: string; sources: GaResourceRef[] }>()
const html = computed(() => {
  const markdown = resolveWebCitations(props.content, props.sources).replace(/^#\s+(.*)$/gm, '## $1')
  const clean = DOMPurify.sanitize(marked.parse(markdown, { breaks: true, gfm: true }) as string, {
    ALLOWED_TAGS: ['p', 'h2', 'h3', 'strong', 'em', 'ul', 'ol', 'li', 'blockquote', 'code', 'pre', 'a', 'br', 'hr'],
    ALLOWED_ATTR: ['href', 'title'], ALLOW_DATA_ATTR: false, ALLOWED_URI_REGEXP: /^https?:[^\s]*$/i,
  })
  const template = document.createElement('template')
  template.innerHTML = clean
  template.content.querySelectorAll('a').forEach(link => { link.target = '_blank'; link.rel = 'noopener noreferrer' })
  return template.innerHTML
})
</script>
<template><div class="ga-cited-answer" data-test="ga-cited-answer" v-html="html" /></template>
<style scoped>
.ga-cited-answer { font-size: 13.5px; line-height: 1.65; overflow-wrap: anywhere; }
.ga-cited-answer :deep(p) { margin: 0 0 8px; }
.ga-cited-answer :deep(a) { color: var(--color-accent-strong); text-decoration: underline; }
.ga-cited-answer :deep(pre) { white-space: pre-wrap; }
</style>
