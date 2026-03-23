<script setup lang="ts">
import { computed } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

const props = defineProps<{
  content: string
}>()

const htmlContent = computed(() => {
  if (!props.content) return ''
  const rawHtml = marked.parse(props.content, { async: false }) as string
  return DOMPurify.sanitize(rawHtml)
})
</script>

<template>
  <div 
    class="prose prose-sm sm:prose-base prose-slate dark:prose-invert max-w-none break-words"
    v-html="htmlContent"
  ></div>
</template>

<style>
/* 针对 markdown 渲染的一些基础样式调整 */
.prose p {
  margin-top: 0.5em;
  margin-bottom: 0.5em;
}
.prose ul, .prose ol {
  margin-top: 0.5em;
  margin-bottom: 0.5em;
}
.prose li p {
  margin-top: 0.25em;
  margin-bottom: 0.25em;
}
.prose pre {
  margin-top: 1em;
  margin-bottom: 1em;
  padding: 1em;
  background-color: #1e293b;
  color: #f8fafc;
  border-radius: 0.5rem;
  overflow-x: auto;
}
.prose pre code {
  background-color: transparent;
  padding: 0;
  border-radius: 0;
  color: inherit;
}
</style>
