import 'server-only'

import { aiConfig } from '@/lib/ai/AiConfig'

type AiMessage = {
  role: 'system' | 'user' | 'assistant'
  content: string
}

type ChatOverrides = {
  model?: string
  temperature?: number
  maxTokens?: number
  timeoutSeconds?: number
}

export class AiClient {
  async chat(systemPrompt: string, userMessage: string, overrides: ChatOverrides = {}) {
    // Most business code only needs a single system-plus-user exchange. More
    // advanced workflows can drop down to chatMessages(...) directly.
    return this.chatMessages(
      [
        { role: 'system', content: systemPrompt },
        { role: 'user', content: userMessage }
      ],
      overrides
    )
  }

  async chatMessages(messages: AiMessage[], overrides: ChatOverrides = {}) {
    // Expose a string-in, string-out helper so generated server code can treat
    // AI calls like any other service dependency.
    const response = await this.request('/chat/completions', this.buildBody(messages, false, overrides))
    const payload = (await response.json()) as {
      choices?: Array<{ message?: { content?: string } }>
    }

    return payload.choices?.[0]?.message?.content ?? ''
  }

  async *chatStream(
    systemPrompt: string,
    userMessage: string,
    overrides: ChatOverrides = {}
  ): AsyncGenerator<string, void, void> {
    yield* this.chatMessagesStream(
      [
        { role: 'system', content: systemPrompt },
        { role: 'user', content: userMessage }
      ],
      overrides
    )
  }

  async *chatMessagesStream(
    messages: AiMessage[],
    overrides: ChatOverrides = {}
  ): AsyncGenerator<string, void, void> {
    const response = await this.request('/chat/completions', this.buildBody(messages, true, overrides))
    const reader = response.body?.getReader()
    if (!reader) {
      throw new Error('AI stream response body is not readable')
    }

    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) {
        break
      }

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split(/\r?\n/)
      buffer = lines.pop() ?? ''

      for (const rawLine of lines) {
        const line = rawLine.trim()
        // Streaming APIs send control frames alongside text frames. Downstream
        // code only needs the assistant delta content.
        if (!line.startsWith('data: ') || line === 'data: [DONE]') {
          continue
        }

        try {
          const payload = JSON.parse(line.slice(6)) as {
            choices?: Array<{ delta?: { content?: string } }>
          }
          const content = payload.choices?.[0]?.delta?.content ?? ''
          if (content) {
            yield content
          }
        } catch {
          continue
        }
      }
    }
  }

  private buildBody(messages: AiMessage[], stream: boolean, overrides: ChatOverrides) {
    // Centralize request shaping so future features like JSON mode or tool
    // calling can be added without changing every caller.
    const body: Record<string, unknown> = {
      model: overrides.model ?? aiConfig.model,
      messages,
      stream,
      temperature: overrides.temperature ?? aiConfig.temperature
    }

    const maxTokens = overrides.maxTokens ?? aiConfig.maxTokens
    if (maxTokens > 0) {
      body.max_tokens = maxTokens
    }

    return body
  }

  private async request(path: string, body: Record<string, unknown>) {
    const controller = new AbortController()
    const timeout = setTimeout(() => controller.abort(), (aiConfig.timeoutSeconds ?? 60) * 1000)

    try {
      // Route all outbound calls through one helper so timeout handling, auth,
      // and provider compatibility stay consistent across generated features.
      const response = await fetch(`${aiConfig.baseUrl.replace(/\/+$/, '')}${path}`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${aiConfig.apiKey}`
        },
        body: JSON.stringify(body),
        signal: controller.signal,
        cache: 'no-store'
      })

      if (!response.ok) {
        throw new Error(`AI API error ${response.status}: ${await response.text()}`)
      }

      return response
    } finally {
      clearTimeout(timeout)
    }
  }
}

export const aiClient = new AiClient()
