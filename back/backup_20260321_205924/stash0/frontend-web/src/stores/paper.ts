import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { PaperOutlineGenerateRequest, PaperOutlineResult } from '@/types/api'

export type PaperDraft = PaperOutlineGenerateRequest

export const usePaperStore = defineStore('paper', () => {
  const draft = ref<PaperDraft>({
    topic: '',
    discipline: '',
    degreeLevel: '',
    methodPreference: ''
  })
  const taskId = ref('')
  const outline = ref<PaperOutlineResult | null>(null)

  const hasTask = computed(() => Boolean(taskId.value))
  const hasOutline = computed(() => outline.value != null)

  const setDraft = (next: Partial<PaperDraft>) => {
    draft.value = {
      ...draft.value,
      ...next
    }
  }

  const reset = () => {
    draft.value = {
      topic: '',
      discipline: '',
      degreeLevel: '',
      methodPreference: ''
    }
    taskId.value = ''
    outline.value = null
  }

  return {
    draft,
    taskId,
    outline,
    hasTask,
    hasOutline,
    setDraft,
    reset
  }
})
