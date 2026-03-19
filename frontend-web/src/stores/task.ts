import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { taskApi } from '@/api/endpoints'
import type { TaskStatusResult, TaskLogResult } from '@/types/api'

export const useTaskStore = defineStore('task', () => {
  const taskId = ref('')
  const rawStatus = ref<TaskStatusResult | null>(null)
  const status = ref('queued')
  const progress = ref(0)
  const currentStep = ref('')
  const logs = ref<TaskLogResult[]>([])

  const isFinished = computed(() => status.value === 'finished')
  const isFailed = computed(() => status.value === 'failed')
  const isCancelled = computed(() => status.value === 'cancelled')

  const setFromStatus = (s: TaskStatusResult) => {
    rawStatus.value = s
    status.value = s.status
    progress.value = s.progress
    currentStep.value = s.current_step ?? s.step ?? ''
  }

  const loadStatus = async (id: string) => {
    taskId.value = id
    const s = await taskApi.status(id)
    setFromStatus(s)
    const logsRes = await taskApi.logs(id)
    logs.value = logsRes
  }

  const reset = () => {
    taskId.value = ''
    rawStatus.value = null
    status.value = 'queued'
    progress.value = 0
    currentStep.value = ''
    logs.value = []
  }

  return {
    taskId,
    rawStatus,
    status,
    progress,
    currentStep,
    logs,
    isFinished,
    isFailed,
    isCancelled,
    loadStatus,
    reset
  }
})
