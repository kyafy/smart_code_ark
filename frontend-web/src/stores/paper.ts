import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type {
  PaperOutlineGenerateRequest,
  PaperOutlineResult,
  TopicSuggestResult,
} from '@/types/api'

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
  const suggestionSessionId = ref<number | null>(null)
  const suggestionRound = ref(0)
  const suggestedTopics = ref<TopicSuggestResult['suggestions']>([])

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
    suggestionSessionId.value = null
    suggestionRound.value = 0
    suggestedTopics.value = []
  }

  return {
    draft,
    taskId,
    outline,
    suggestionSessionId,
    suggestionRound,
    suggestedTopics,
    hasTask,
    hasOutline,
    setDraft,
    reset
  }
})
