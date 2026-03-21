import { defineStore } from 'pinia'
import { ref } from 'vue'
import { chatApi } from '@/api/endpoints'

export type ChatMessage = { role: 'user' | 'assistant'; content: string; status?: 'pending' | 'streaming' | 'done' | 'error' }

export const useChatStore = defineStore('mobile-chat', () => {
  const sessionId = ref('new')
  const messages = ref<ChatMessage[]>([])

  const loadSession = async (sid: string) => {
    sessionId.value = sid
    const data = await chatApi.getSessionMessages(sid)
    messages.value = (data.messages || []).map((m) => ({ role: m.role as 'user' | 'assistant', content: m.content, status: 'done' }))
  }

  const sendMessage = async (content: string) => {
    if (!content.trim()) return
    if (sessionId.value === 'new') {
      const start = await chatApi.start({ title: content.slice(0, 20), projectType: 'mobile' })
      sessionId.value = start.sessionId
    }
    messages.value.push({ role: 'user', content, status: 'done' })
    messages.value.push({ role: 'assistant', content: '', status: 'pending' })
    const target = messages.value[messages.value.length - 1]
    const sentAt = Date.now()
    let streamFailed = false
    try {
      await chatApi.send({ sessionId: sessionId.value, message: content }, (event, data) => {
        if (event === 'delta') {
          target.status = 'streaming'
          target.content += String(data ?? '')
        }
        if (event === 'result') {
          target.status = 'done'
        }
        if (event === 'error') {
          target.status = 'error'
        }
      })
    } catch {
      streamFailed = true
    }
    if (target.status === 'done' && target.content.trim()) return
    if (streamFailed) {
      target.status = 'pending'
    }
    await pollAssistantResult(target, sentAt)
  }

  const pollAssistantResult = async (target: ChatMessage, sentAt: number) => {
    for (let i = 0; i < 12; i++) {
      await wait(1500)
      try {
        const data = await chatApi.getSessionMessages(sessionId.value)
        const assistantMessages = (data.messages || []).filter((m) => m.role === 'assistant')
        const latest = assistantMessages[assistantMessages.length - 1]
        if (!latest?.content) continue
        const updatedAt = Date.parse(String(data.updatedAt ?? ''))
        if (!Number.isNaN(updatedAt) && updatedAt < sentAt) continue
        target.content = String(latest.content)
        target.status = 'done'
        return
      } catch {
      }
    }
    target.status = 'error'
  }

  const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

  return { sessionId, messages, loadSession, sendMessage, pollAssistantResult }
})
