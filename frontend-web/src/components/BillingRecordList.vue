<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { BillingRecordResult } from '@/types/api'

const props = defineProps<{
  records: BillingRecordResult[]
}>()

const pageSize = 8
const visibleCount = ref(pageSize)

watch(
  () => props.records,
  () => {
    visibleCount.value = pageSize
  },
  { deep: true }
)

const sorted = computed(() => [...props.records].sort((a, b) =>
  Date.parse(b.createdAt) - Date.parse(a.createdAt)
))

const visibleRecords = computed(() => sorted.value.slice(0, visibleCount.value))
const hasMore = computed(() => sorted.value.length > visibleCount.value)

const formatTime = (time: string) => {
  const ts = Date.parse(time)
  if (Number.isNaN(ts)) return time
  return new Date(ts).toLocaleString()
}

const loadMore = () => {
  visibleCount.value += pageSize
}
</script>

<template>
  <div class="rounded-2xl border bg-white p-5 shadow-sm dark:border-slate-900 dark:bg-slate-950">
    <div class="mb-4 text-base font-semibold">账单记录</div>

    <div v-if="visibleRecords.length === 0" class="rounded-xl border border-dashed p-8 text-center text-sm text-slate-500 dark:border-slate-800 dark:text-slate-400">
      暂无账单记录
    </div>

    <div v-else class="space-y-3">
      <div
        v-for="item in visibleRecords"
        :key="item.id"
        class="rounded-xl border p-3 dark:border-slate-800"
      >
        <div class="flex items-center justify-between">
          <div class="text-sm font-medium">{{ item.reason || 'recharge' }}</div>
          <div class="text-sm font-semibold" :class="Number(item.changeAmount) >= 0 ? 'text-emerald-600' : 'text-rose-600'">
            {{ Number(item.changeAmount) >= 0 ? '+' : '' }}{{ item.changeAmount }}
          </div>
        </div>
        <div class="mt-1 flex items-center justify-between text-xs text-slate-500 dark:text-slate-400">
          <span>余额/积分：{{ item.balanceAfter }}</span>
          <span>{{ formatTime(item.createdAt) }}</span>
        </div>
      </div>
    </div>

    <div v-if="hasMore" class="mt-4 text-center">
      <button
        type="button"
        class="rounded-lg border px-3 py-1.5 text-sm text-slate-600 hover:bg-slate-50 dark:border-slate-800 dark:text-slate-300 dark:hover:bg-slate-900"
        @click="loadMore"
      >
        加载更多
      </button>
    </div>
  </div>
</template>
