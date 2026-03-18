import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { taskApi } from '@/api/endpoints'
import type { TaskStatusResult } from '@/types/api'

export type TaskLog = {
  id: string
  level: 'info' | 'warn' | 'error'
  content: string
  ts: number
}

export const useTaskStore = defineStore('task', () => {
  const taskId = ref('')
  const status = ref('')
  const progress = ref(0)
  const currentStep = ref('')
  const logs = ref<TaskLog[]>([])

  const isFinished = computed(() => status.value === 'finished')
  const isFailed = computed(() => status.value === 'failed' || status.value === 'timeout')

  const setFromStatus = (s: TaskStatusResult) => {
    status.value = s.status
    progress.value = s.progress
    currentStep.value = s.current_step ?? s.step ?? ''
  }

  const loadStatus = async (id: string) => {
    taskId.value = id
    const s = await taskApi.status(id)
    setFromStatus(s)
    logs.value.push({
      id: `l_${Math.random().toString(36).slice(2, 10)}`,
      level: 'info',
      content: `状态更新：${s.status} ${s.progress}% ${s.current_step ?? s.step ?? ''}`,
      ts: Date.now(),
    })
  }

  const reset = () => {
    taskId.value = ''
    status.value = ''
    progress.value = 0
    currentStep.value = ''
    logs.value = []
  }

  return {
    taskId,
    status,
    progress,
    currentStep,
    logs,
    isFinished,
    isFailed,
    loadStatus,
    reset,
  }
})

