import 'server-only'

function readNumber(value: string | undefined, fallback: number) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : fallback
}

export class AiConfig {
  baseUrl = process.env.AI_BASE_URL ?? 'https://dashscope.aliyuncs.com/compatible-mode/v1'
  apiKey = process.env.AI_API_KEY ?? ''
  model = process.env.AI_MODEL ?? 'qwen-plus'
  embeddingModel = process.env.AI_EMBEDDING_MODEL ?? 'text-embedding-v3'
  timeoutSeconds = readNumber(process.env.AI_TIMEOUT_SECONDS, 60)
  maxTokens = readNumber(process.env.AI_MAX_TOKENS, 4096)
  temperature = readNumber(process.env.AI_TEMPERATURE, 0.7)
}

export const aiConfig = new AiConfig()
