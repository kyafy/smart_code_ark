<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { QUERY_FROM, QUERY_SOURCE_TASK_ID } from '@/constants/navigation'

const route = useRoute()

const sourceTaskId = computed(() => String(route.query[QUERY_SOURCE_TASK_ID] ?? ''))
const from = computed(() => String(route.query[QUERY_FROM] ?? ''))
</script>

<template>
  <div class="rounded-2xl border bg-white p-6">
    <div class="text-base font-semibold">论文框架（Phase 1）</div>
    <div class="mt-2 text-sm text-slate-600">该功能入口与数据结构已预留，后续 Phase 将逐步接入页面与流程。</div>
    <div class="mt-4 text-xs text-slate-500">
      <div>route: {{ route.path }}</div>
      <div v-if="from">from: {{ from }}</div>
      <div v-if="sourceTaskId">sourceTaskId: {{ sourceTaskId }}</div>
    </div>
  </div>
</template>
