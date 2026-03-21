<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import type { TaskLogResult } from '@/types/api'

const props = defineProps<{ logs: TaskLogResult[]; showHint?: boolean }>()

const sorted = computed(() => [...props.logs].sort((a, b) => a.ts - b.ts))
const logContainerRef = ref<HTMLDivElement | null>(null)

const scrollToBottom = async () => {
  await nextTick()
  if (logContainerRef.value) {
    logContainerRef.value.scrollTop = logContainerRef.value.scrollHeight
  }
}

watch(() => props.logs, () => {
  void scrollToBottom()
}, { deep: true })

onMounted(() => {
  void scrollToBottom()
})
</script>

<template>
  <div class="rounded-2xl border bg-white">
    <div class="flex items-center justify-between border-b px-4 py-3">
      <div class="text-sm font-semibold">实时日志</div>
      <div v-if="props.showHint" class="text-xs text-slate-500">当前已降级为轮询</div>
    </div>
    <div ref="logContainerRef" class="h-[260px] overflow-auto p-3 scroll-smooth">
      <div v-if="sorted.length === 0" class="py-10 text-center text-sm text-slate-500">暂无日志</div>
      <div v-else class="flex flex-col gap-2">
        <div
          v-for="l in sorted"
          :key="l.id"
          class="rounded-xl px-3 py-2 text-xs"
          :class="
            l.level === 'error'
              ? 'bg-red-50 text-red-700'
              : l.level === 'warn'
                ? 'bg-amber-50 text-amber-700'
                : 'bg-slate-50 text-slate-700'
          "
        >
          <div class="flex items-center justify-between gap-3">
            <div class="truncate">{{ l.content }}</div>
            <div class="shrink-0 text-[11px] text-slate-400">{{ new Date(l.ts).toLocaleTimeString() }}</div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

