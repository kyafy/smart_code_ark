<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import type { ChatMessage } from '@/stores/chat'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'
import AssistantThinkingCard from '@/components/AssistantThinkingCard.vue'
import { AlertCircle, RefreshCcw } from 'lucide-vue-next'

const props = defineProps<{ messages: ChatMessage[] }>()
const emit = defineEmits<{
  (e: 'retry', messageIndex: number): void
}>()

const rootRef = ref<HTMLDivElement | null>(null)
const sorted = computed(() => [...props.messages].sort((a, b) => a.createdAt - b.createdAt))

const onRetry = (m: ChatMessage) => {
  const idx = props.messages.findIndex((x) => x.id === m.id)
  if (idx !== -1) {
    emit('retry', idx)
  }
}

const scrollToBottom = async () => {
  await nextTick()
  const el = rootRef.value
  if (!el) return
  el.scrollTop = el.scrollHeight
}

onMounted(scrollToBottom)
watch(
  () => props.messages,
  () => {
    void scrollToBottom()
  },
  { deep: true }
)
</script>

<template>
  <div ref="rootRef" class="h-[520px] overflow-auto rounded-2xl border bg-slate-50/50 p-4 dark:bg-slate-900/50 dark:border-slate-800">
    <div class="flex flex-col gap-6">
      <div
        v-for="m in sorted"
        :key="m.id"
        class="flex w-full"
        :class="m.speaker === 'user' ? 'justify-end' : 'justify-start'"
      >
        <div
          class="max-w-[85%] rounded-2xl px-5 py-4 text-sm shadow-sm"
          :class="[
            m.speaker === 'user'
              ? 'bg-blue-600 text-white rounded-br-none'
              : 'bg-white text-slate-800 dark:bg-slate-950 dark:text-slate-200 border border-slate-100 dark:border-slate-800 rounded-bl-none',
            m.status === 'error' ? 'border-red-200 bg-red-50 dark:border-red-900/50 dark:bg-red-950/20' : ''
          ]"
        >
          <div v-if="m.speaker === 'user'" class="whitespace-pre-wrap leading-relaxed">
            {{ m.message }}
          </div>
          <div v-else-if="m.status === 'pending'">
            <AssistantThinkingCard />
          </div>
          <div v-else-if="m.status === 'error'" class="flex flex-col gap-3 text-red-600 dark:text-red-400">
            <div class="flex items-center gap-2 font-semibold">
              <AlertCircle class="h-4 w-4" />
              <span>生成失败</span>
            </div>
            <div class="text-xs opacity-80">{{ m.errorMessage || '请求超时或网络异常，请稍后重试' }}</div>
            <div>
              <button
                type="button"
                class="flex items-center gap-1.5 rounded-lg border border-red-200 bg-white px-3 py-1.5 text-xs font-medium text-red-600 shadow-sm transition-colors hover:bg-red-50 dark:border-red-900 dark:bg-slate-950 dark:hover:bg-red-950/50"
                @click="onRetry(m)"
              >
                <RefreshCcw class="h-3 w-3" />
                重试
              </button>
            </div>
          </div>
          <div v-else class="relative">
            <MarkdownRenderer :content="m.message" class="text-sm" />
            <div v-if="m.status === 'streaming'" class="mt-2 flex items-center gap-2 text-xs text-slate-400 dark:text-slate-500">
              <span class="relative flex h-2 w-2">
                <span class="animate-ping absolute inline-flex h-full w-full rounded-full bg-blue-400 opacity-75 dark:bg-blue-500"></span>
                <span class="relative inline-flex rounded-full h-2 w-2 bg-blue-500 dark:bg-blue-400"></span>
              </span>
              持续生成中...
            </div>
          </div>
        </div>
      </div>

      <div v-if="sorted.length === 0" class="py-10 text-center text-sm text-slate-500">
        请输入你的毕设需求，我会先通过对话帮你梳理功能和数据库。
      </div>
    </div>
  </div>
</template>

