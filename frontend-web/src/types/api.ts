export type ApiResponse<T> = {
  code: number
  message: string
  data: T
}

export type StackConfig = {
  backend: string
  frontend: string
  db: string
}

export type AuthLoginResult = {
  token: string
  userId: number | string
}

export type SmsSendResult = {
  requestId: string
  expireIn: number
}

export type ChatStartResult = {
  sessionId: string
  stage: 'requirement' | string
}

export type ChatReplyResult = {
  sessionId: string
  messages: Array<{
    role: string
    content: string
  }>
  extractedRequirements?: any
  createdAt?: string
  updatedAt?: string
}

export type ProjectConfirmResult = {
  projectId: string
  status: string
}

export type ProjectSummary = {
  id: string
  title: string
  description?: string
  status: string
  updatedAt: string
}

export type TaskSummary = {
  id: string
  projectId: string
  taskType: string
  status: string
  progress: number
  errorMessage?: string
  createdAt: string
  updatedAt: string
}

export type ProjectDetail = {
  id: string
  title: string
  description: string
  projectType: string
  status: string
  stack: StackConfig
  requirementSpec: string
  createdAt: string
  updatedAt: string
  tasks: TaskSummary[]
  messages: Array<{ role: string; content: string }>
}

export type GenerateResult = {
  taskId: string
  status: string
}

export interface TaskStatusResult {
  status: 'queued' | 'running' | 'finished' | 'failed' | 'cancelled' | 'timeout'
  progress: number
  step: string
  current_step: string
  projectId?: string
  errorCode?: string
  errorMessage?: string
  startedAt?: string
  finishedAt?: string
}

export interface TaskLogResult {
  id: number
  level: string
  content: string
  ts: number
}

export type TaskPreviewResult = {
  previewUrl: string
}
