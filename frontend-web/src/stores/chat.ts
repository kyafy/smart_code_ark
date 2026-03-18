import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { chatApi } from '@/api/endpoints'

export type ChatMessage = {
  id: string
  speaker: 'user' | 'assistant'
  message: string
  createdAt: number
}

export const useChatStore = defineStore('chat', () => {
  const sessionId = ref('')
  const title = ref('')
  const messages = ref<ChatMessage[]>([])
  const draftModules = ref<string[]>([])
  const extractedRequirements = ref<any>(null)
  const isSending = ref(false)

  const hasDraft = computed(() => extractedRequirements.value != null || draftModules.value.length > 0)

  const startSession = async (payload: { title: string; projectType: string; description?: string }) => {
    const res = await chatApi.start(payload)
    sessionId.value = res.sessionId
    title.value = payload.title
    messages.value = []
    draftModules.value = []
    extractedRequirements.value = null
    return res.sessionId
  }

  const hydrateFromMock = (sid: string) => {
    if (!sid) return
    if (import.meta.env.VITE_USE_MOCK === 'false') return
    try {
      const raw = localStorage.getItem('__smartark_mock_state__')
      if (!raw) return
      const state = JSON.parse(raw) as any
      const session = Array.isArray(state.sessions)
        ? state.sessions.find((s: any) => String(s.sessionId) === sid)
        : null
      if (!session) return

      sessionId.value = sid
      title.value = String(session.title ?? '')
      draftModules.value = Array.isArray(session.draftModules)
        ? session.draftModules.map((m: any) => String(m))
        : []
      extractedRequirements.value = session.extractedRequirements || null

      const rawMessages = Array.isArray(session.messages) ? session.messages : []
      const baseTs = typeof session.updatedAt === 'number' ? session.updatedAt : Date.now()
      messages.value = rawMessages.map((m: any, idx: number) => {
        const speaker = String(m.speaker) === 'user' ? 'user' : 'assistant'
        return {
          id: `m_${sid}_${idx}`,
          speaker,
          message: String(m.message ?? ''),
          createdAt: baseTs - (rawMessages.length - idx) * 2000,
        } satisfies ChatMessage
      })
    } catch {
      return
    }
  }

  const loadSession = async (sid: string) => {
    try {
      const res = await chatApi.getSessionMessages(sid)
      sessionId.value = res.sessionId
      // If we don't have the title in the response, we might need to fetch it or keep the current one
      
      const baseTs = new Date(res.updatedAt || Date.now()).getTime()
      
      if (res.messages && Array.isArray(res.messages)) {
        messages.value = res.messages.map((m: any, idx: number) => {
          return {
            id: `m_${sid}_${idx}`,
            speaker: m.role === 'user' ? 'user' : 'assistant',
            message: m.content || '',
            createdAt: baseTs - (res.messages.length - idx) * 2000,
          } satisfies ChatMessage
        })
      } else {
        messages.value = []
      }
      
      extractedRequirements.value = res.extractedRequirements || null
      // Clear draft modules as we are using extractedRequirements now
      draftModules.value = []
    } catch (e) {
      console.error('Failed to load session messages', e)
    }
  }

  const send = async (text: string) => {
    if (!sessionId.value) throw new Error('sessionId is empty')
    isSending.value = true
    try {
      const userMsg: ChatMessage = {
        id: `m_${Math.random().toString(36).slice(2, 10)}`,
        speaker: 'user',
        message: text,
        createdAt: Date.now(),
      }
      messages.value.push(userMsg)

      const aiMsgId = `m_${Math.random().toString(36).slice(2, 10)}`
      const aiMsg: ChatMessage = {
        id: aiMsgId,
        speaker: 'assistant',
        message: '',
        createdAt: Date.now(),
      }
      messages.value.push(aiMsg)
      const aiMsgIndex = messages.value.length - 1

      await chatApi.send({ sessionId: sessionId.value, message: text }, (event, data) => {
        if (event === 'delta') {
          messages.value[aiMsgIndex].message += String(data)
        } else if (event === 'result') {
          if (data.extractedRequirements) {
            extractedRequirements.value = data.extractedRequirements
          }
          if (data.messages && data.messages.length > 0) {
             const lastMsg = data.messages[data.messages.length - 1]
             if (lastMsg.role === 'assistant') {
                 messages.value[aiMsgIndex].message = lastMsg.content
             }
          }
        }
      })
    } finally {
      isSending.value = false
    }
  }

  const reset = () => {
    sessionId.value = ''
    title.value = ''
    messages.value = []
    draftModules.value = []
    extractedRequirements.value = null
  }

  return {
    sessionId,
    title,
    messages,
    draftModules,
    extractedRequirements,
    hasDraft,
    isSending,
    startSession,
    hydrateFromMock,
    loadSession,
    send,
    reset,
  }
})
