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

export type UserProfileResult = {
  userId: number
  username: string
  balance: number
  quota: number
  createdAt: string
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

export type GenerateOptions = {
  deliveryLevel?: 'draft' | 'validated' | 'deliverable' | string
  templateId?: string | null
  strictDelivery?: boolean
  enablePreview?: boolean
  enableAutoRepair?: boolean
}

export type DeliveryReportResult = {
  taskId: string
  deliveryLevelRequested: 'draft' | 'validated' | 'deliverable' | string
  deliveryLevelActual: 'draft' | 'validated' | 'deliverable' | string
  status: 'pending' | 'passed' | 'failed' | 'degraded' | string
  passed: boolean
  blockingIssues: Array<{
    stage: string
    code: string
    message: string
    logRef?: string | null
  }>
  warnings: string[]
  reports: {
    contractReportUrl?: string | null
    buildReportUrl?: string | null
    runtimeSmokeReportUrl?: string | null
  }
  generatedAt: string
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
  taskId: string
  status: 'provisioning' | 'ready' | 'failed' | 'expired' | string
  phase?: 'prepare_artifact' | 'start_runtime' | 'install_deps' | 'boot_service' | 'health_check' | 'publish_gateway' | string | null
  previewUrl?: string | null
  expireAt?: string | null
  lastError?: string | null
  lastErrorCode?: number | null
  buildLogUrl?: string | null
}

export type PreviewLogsResult = {
  taskId: string
  logs: Array<{ ts: number; level: string; message: string }>
}

export type BalanceResult = {
  balance: number
  quota: number
}

export type BillingRecordResult = {
  id: number
  projectId?: string | null
  taskId?: string | null
  changeAmount: number
  currency: string
  reason: string
  balanceAfter: number
  createdAt: string
}

export type RechargeOrderResult = {
  orderId: string
  status: 'pending' | 'paid' | string
  amount: number
  quota: number
  payChannel: string
  paymentNo?: string | null
  paidAt?: string | null
  createdAt: string
  updatedAt: string
}

export type PaperOutlineGenerateRequest = {
  topic: string
  discipline: string
  degreeLevel: string
  methodPreference?: string
}

export type PaperOutlineGenerateResult = {
  taskId: string
  status: string
}

export type PaperOutlineResult = {
  taskId: string
  citationStyle: string
  topic: string
  topicRefined: string
  researchQuestions: string[]
  chapters: unknown
  manuscript?: unknown
  qualityChecks: unknown
  references: unknown
  qualityScore?: number
  rewriteRound?: number
}

export type PaperManuscriptResult = {
  taskId: string
  topic: string
  topicRefined: string
  manuscript: unknown
  qualityScore?: number
  rewriteRound?: number
}

export type PaperProjectSummary = {
  taskId: string
  topic: string
  discipline: string
  degreeLevel: string
  status: string
  updatedAt?: string | null
}

export type RagRetrievalResult = {
  taskId: string
  evidenceItems: Array<{
    chunkUid: string
    docUid?: string | null
    paperId?: string | null
    title?: string | null
    content?: string | null
    url?: string | null
    year?: number | null
    vectorScore?: number | null
    rerankScore?: number | null
    chunkType?: string | null
  }>
  totalChunks: number
}

export type TopicSuggestRequest = {
  sessionId?: number
  direction: string
  constraints?: string
  round?: number
}

export type TopicSuggestResult = {
  sessionId: number
  round: number
  suggestions: Array<{
    title: string
    researchQuestions: string[]
    rationale: string
    keywords: string[]
  }>
}

export type TopicAdoptRequest = {
  sessionId: number
  selectedIndex: number
  customTitle?: string
  customQuestions?: string[]
}

export type PaperTraceabilityResult = {
  taskId: string
  chapters: Array<{
    chapterTitle: string
    chapterIndex: number
    citationIndices: number[]
  }>
  globalEvidenceList: Array<{
    citationIndex: number
    chunkUid: string
    docUid: string
    paperId: string
    title: string
    content: string
    url: string
    year?: number | null
    source?: string | null
    vectorScore?: number | null
    rerankScore?: number | null
    chunkType?: string | null
  }>
  totalChunksSearched: number
}
