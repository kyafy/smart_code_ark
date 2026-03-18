<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ status: string }>()

const label = computed(() => {
  const s = props.status
  if (s === 'queued') return '排队中'
  if (s === 'running') return '生成中'
  if (s === 'finished') return '已完成'
  if (s === 'failed') return '失败'
  if (s === 'timeout') return '超时'
  return s || '-'
})

const type = computed(() => {
  const s = props.status
  if (s === 'finished') return 'success'
  if (s === 'failed' || s === 'timeout') return 'danger'
  if (s === 'running') return 'primary'
  if (s === 'queued') return 'info'
  return 'info'
})
</script>

<template>
  <el-tag :type="type" effect="light">{{ label }}</el-tag>
</template>

