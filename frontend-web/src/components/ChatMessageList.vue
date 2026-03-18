<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import type { ChatMessage } from '@/stores/chat'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'

const props = defineProps<{ messages: ChatMessage[] }>()

const rootRef = ref<HTMLDivElement | null>(null)
const sorted = computed(() => [...props.messages].sort((a, b) => a.createdAt - b.createdAt))

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
          :class="
            m.speaker === 'user'
              ? 'bg-blue-600 text-white rounded-br-none'
              : 'bg-white text-slate-800 dark:bg-slate-950 dark:text-slate-200 border border-slate-100 dark:border-slate-800 rounded-bl-none'
          "
        >
          <div v-if="m.speaker === 'user'" class="whitespace-pre-wrap leading-relaxed">
            {{ m.message }}
          </div>
          <MarkdownRenderer v-else :content="m.message" class="text-sm" />
        </div>
      </div>

      <div v-if="sorted.length === 0" class="py-10 text-center text-sm text-slate-500">
        请输入你的毕设需求，我会先通过对话帮你梳理功能和数据库。
      </div>
    </div>
  </div>
</template>

