import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { chatApi } from '@/api/endpoints'

export type ChatMessage = {
  id: string
  speaker: 'user' | 'assistant'
  message: string
  createdAt: number
  status?: 'pending' | 'streaming' | 'done' | 'error'
  errorMessage?: string
}

export const useChatStore = defineStore('chat', () => {
  const sessionId = ref('')
  const title = ref('')
  const messages = ref<ChatMessage[]>([])
  const draftModules = ref<string[]>([])
  const extractedRequirements = ref<any>(null)
  const isSending = ref(false)
  let loadVersion = 0
  let streamController: AbortController | null = null

  const hasDraft = computed(() => extractedRequirements.value != null || draftModules.value.length > 0)

  const startSession = async (payload: { title: string; projectType: string; description?: string }) => {
    if (streamController) {
      streamController.abort()
      streamController = null
    }
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
    if (streamController) {
      streamController.abort()
      streamController = null
    }
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
          status: 'done',
        } satisfies ChatMessage
      })
    } catch {
      return
    }
  }

  const loadSession = async (sid: string) => {
    const currentLoad = ++loadVersion
    if (streamController) {
      streamController.abort()
      streamController = null
    }
    sessionId.value = sid
    messages.value = []
    extractedRequirements.value = null
    draftModules.value = []
    try {
      const res = await chatApi.getSessionMessages(sid)
      if (currentLoad !== loadVersion) return
      sessionId.value = res.sessionId
      
      const baseTs = new Date(res.updatedAt || Date.now()).getTime()
      
      if (res.messages && Array.isArray(res.messages)) {
        messages.value = res.messages.map((m: any, idx: number) => {
          return {
            id: `m_${sid}_${idx}`,
            speaker: m.role === 'user' ? 'user' : 'assistant',
            message: m.content || '',
            createdAt: baseTs - (res.messages.length - idx) * 2000,
            status: 'done',
          } satisfies ChatMessage
        })
      } else {
        messages.value = []
      }
      
      extractedRequirements.value = res.extractedRequirements || null
      draftModules.value = []
    } catch (e) {
      if (currentLoad !== loadVersion) return
      if (sid === sessionId.value) {
        messages.value = []
        extractedRequirements.value = null
      }
      console.error('Failed to load session messages', e)
    }
  }

  const send = async (text: string) => {
    if (!sessionId.value) throw new Error('sessionId is empty')
    if (streamController) {
      streamController.abort()
      streamController = null
    }
    const activeSid = sessionId.value
    const controller = new AbortController()
    streamController = controller
    isSending.value = true
    try {
      const userMsg: ChatMessage = {
        id: `m_${Math.random().toString(36).slice(2, 10)}`,
        speaker: 'user',
        message: text,
        createdAt: Date.now(),
        status: 'done',
      }
      messages.value.push(userMsg)

      const aiMsgId = `m_${Math.random().toString(36).slice(2, 10)}`
      const aiMsg: ChatMessage = {
        id: aiMsgId,
        speaker: 'assistant',
        message: '',
        createdAt: Date.now(),
        status: 'pending',
      }
      messages.value.push(aiMsg)
      const aiMsgIndex = messages.value.length - 1

      await chatApi.send({ sessionId: activeSid, message: text }, (event, data) => {
        if (sessionId.value !== activeSid) return
        if (event === 'delta') {
          messages.value[aiMsgIndex].status = 'streaming'
          messages.value[aiMsgIndex].message += String(data)
        } else if (event === 'result') {
          messages.value[aiMsgIndex].status = 'done'
          if (data.extractedRequirements) {
            extractedRequirements.value = data.extractedRequirements
          }
          if (data.messages && data.messages.length > 0) {
             const lastMsg = data.messages[data.messages.length - 1]
             if (lastMsg.role === 'assistant') {
                 messages.value[aiMsgIndex].message = lastMsg.content
             }
          }
        } else if (event === 'error') {
          messages.value[aiMsgIndex].status = 'error'
          messages.value[aiMsgIndex].errorMessage = String(data.message || '生成出错')
        }
      }, { signal: controller.signal })
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') {
        return
      }
      if (messages.value.length > 0) {
        const lastMsg = messages.value[messages.value.length - 1]
        if (lastMsg.speaker === 'assistant') {
          lastMsg.status = 'error'
          lastMsg.errorMessage = e instanceof Error ? e.message : '发送失败'
        }
      }
      throw e
    } finally {
      if (streamController === controller) {
        streamController = null
      }
      isSending.value = false
    }
  }

  const reset = () => {
    if (streamController) {
      streamController.abort()
      streamController = null
    }
    loadVersion += 1
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
