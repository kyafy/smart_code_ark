import 'server-only'

import { aiConfig } from '@/lib/ai/AiConfig'

export class EmbeddingClient {
  async embed(text: string) {
    const [vector] = await this.embedBatch([text])
    return vector
  }

  async embedBatch(texts: string[]) {
    const response = await fetch(`${aiConfig.baseUrl.replace(/\/+$/, '')}/embeddings`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${aiConfig.apiKey}`
      },
      body: JSON.stringify({
        model: aiConfig.embeddingModel,
        input: texts
      }),
      cache: 'no-store'
    })

    if (!response.ok) {
      throw new Error(`Embedding API error ${response.status}: ${await response.text()}`)
    }

    const payload = (await response.json()) as {
      data?: Array<{ embedding?: number[] }>
    }

    return payload.data?.map((item) => item.embedding ?? []) ?? []
  }
}

export const embeddingClient = new EmbeddingClient()
