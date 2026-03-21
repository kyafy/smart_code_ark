<script setup lang="ts">
import { ref, watch } from 'vue'
import { taskApi } from '@/api/endpoints'
import type { PreviewLogsResult } from '@smartark/domain/api'

const props = defineProps<{
  taskId: string
  visible: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
}>()

const logs = ref<PreviewLogsResult['logs']>([])
const isLoading = ref(false)

const fetchLogs = async () => {
  if (!props.taskId) return
  isLoading.value = true
  try {
    const result = await taskApi.previewLogs(props.taskId, 200)
    logs.value = result.logs || []
  } catch {
    logs.value = [{ ts: Date.now(), level: 'error', message: '获取日志失败' }]
  } finally {
    isLoading.value = false
  }
}

watch(() => props.visible, (val) => {
  if (val) fetchLogs()
})

const levelColor = (level: string) => {
  switch (level) {
    case 'error': return '#ef4444'
    case 'warn': return '#f59e0b'
    default: return '#94a3b8'
  }
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    title="预览部署日志"
    width="720px"
    @update:model-value="emit('update:visible', $event)"
  >
    <div class="flex items-center justify-end mb-3">
      <el-button size="small" :loading="isLoading" @click="fetchLogs">刷新日志</el-button>
    </div>
    <div class="log-container">
      <div v-if="logs.length === 0 && !isLoading" class="text-center text-slate-400 py-8 text-sm">
        暂无日志
      </div>
      <div v-for="(line, idx) in logs" :key="idx" class="log-line">
        <span class="log-ts">{{ new Date(line.ts).toLocaleTimeString() }}</span>
        <span class="log-level" :style="{ color: levelColor(line.level) }">[{{ line.level }}]</span>
        <span class="log-msg">{{ line.message }}</span>
      </div>
    </div>
  </el-dialog>
</template>

<style scoped>
.log-container {
  background: #1e293b;
  border-radius: 8px;
  padding: 16px;
  max-height: 400px;
  overflow-y: auto;
  font-family: 'Cascadia Code', 'Fira Code', 'JetBrains Mono', monospace;
  font-size: 12px;
  line-height: 1.6;
}
.log-line {
  white-space: pre-wrap;
  word-break: break-all;
  color: #e2e8f0;
}
.log-ts {
  color: #64748b;
  margin-right: 8px;
}
.log-level {
  margin-right: 8px;
  font-weight: 600;
}
.log-msg {
  color: #e2e8f0;
}
</style>
