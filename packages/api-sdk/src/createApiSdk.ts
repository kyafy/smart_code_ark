import type {
  AuthLoginResult,
  BalanceResult,
  BillingRecordResult,
  ChatReplyResult,
  ChatStartResult,
  DeliveryReportResult,
  GenerateOptions,
  GenerateResult,
  PaperManuscriptResult,
  PaperOutlineGenerateRequest,
  PaperOutlineGenerateResult,
  PaperOutlineResult,
  PaperProjectSummary,
  PaperTraceabilityResult,
  RagRetrievalResult,
  PreviewLogsResult,
  ProjectConfirmResult,
  ProjectDetail,
  ProjectSummary,
  RechargeOrderResult,
  SmsSendResult,
  StackConfig,
  TaskLogResult,
  TaskPreviewResult,
  TaskStatusResult,
  TopicAdoptRequest,
  TopicSuggestRequest,
  TopicSuggestResult,
  UserProfileResult,
} from '@smartark/domain/api'

type RequestJson = <T>(config: { method: string; url: string; data?: unknown; params?: unknown }) => Promise<T>
type RequestBlob = (config: { method: string; url: string; data?: unknown; params?: unknown }) => Promise<Blob>
type RequestSse = (
  url: string,
  payload: { sessionId: string; message: string },
  onMessage: (event: string, data: any) => void,
  onError?: (error: Error) => void,
  options?: { signal?: AbortSignal }
) => Promise<void>

export const createApiSdk = (deps: {
  requestJson: RequestJson
  requestBlob: RequestBlob
  requestSse: RequestSse
}) => {
  const { requestBlob, requestJson, requestSse } = deps

  const authApi = {
    register: (payload: { username: string; password: string; phone?: string }) =>
      requestJson<{ userId: string | number }>({ method: 'POST', url: '/api/auth/register', data: payload }),
    login: (payload: { username: string; password: string }) =>
      requestJson<AuthLoginResult>({ method: 'POST', url: '/api/auth/login', data: payload }),
    smsSend: (payload: { phone: string; scene: 'login' | string }) =>
      requestJson<SmsSendResult>({ method: 'POST', url: '/api/auth/sms/send', data: payload }),
    loginSms: (payload: { phone: string; captcha: string }) =>
      requestJson<AuthLoginResult>({ method: 'POST', url: '/api/auth/login/sms', data: payload }),
  }

  const userApi = {
    profile: () => requestJson<UserProfileResult>({ method: 'GET', url: '/api/user/profile' }),
  }

  const chatApi = {
    start: (payload: { title: string; projectType: string; description?: string }) =>
      requestJson<ChatStartResult>({ method: 'POST', url: '/api/chat/start', data: payload }),
    send: (
      payload: { sessionId: string; message: string },
      onMessage: (event: string, data: any) => void,
      options?: { signal?: AbortSignal }
    ) =>
      requestSse('/api/chat', payload, onMessage, (err) => {
        throw err
      }, options),
    getSessions: () => requestJson<any[]>({ method: 'GET', url: '/api/chat/sessions' }),
    getSessionMessages: (sessionId: string) =>
      requestJson<ChatReplyResult>({ method: 'GET', url: `/api/chat/sessions/${sessionId}/messages` }),
    deleteSession: (sessionId: string) =>
      requestJson<boolean>({ method: 'DELETE', url: `/api/chat/sessions/${sessionId}` }),
  }

  const projectApi = {
    list: () => requestJson<ProjectSummary[]>({ method: 'GET', url: '/api/projects' }),
    detail: (id: string) => requestJson<ProjectDetail>({ method: 'GET', url: `/api/projects/${id}` }),
    confirm: (payload: { sessionId: string; stack: StackConfig; description?: string; prd?: string }) =>
      requestJson<ProjectConfirmResult>({ method: 'POST', url: '/api/projects/confirm', data: payload }),
    delete: (id: string) => requestJson<boolean>({ method: 'DELETE', url: `/api/projects/${id}` }),
  }

  const taskApi = {
    generate: (payload: { projectId: string; instructions?: string; options?: GenerateOptions }) =>
      requestJson<GenerateResult>({ method: 'POST', url: '/api/generate', data: payload }),
    status: (taskId: string) => requestJson<TaskStatusResult>({ method: 'GET', url: `/api/task/${taskId}/status` }),
    deliveryReport: (taskId: string): Promise<DeliveryReportResult> =>
      requestJson<DeliveryReportResult>({ method: 'GET', url: `/api/task/${taskId}/delivery-report` }),
    preview: (taskId: string): Promise<TaskPreviewResult> =>
      requestJson<TaskPreviewResult>({ method: 'GET', url: `/api/task/${taskId}/preview` }),
    rebuildPreview: (taskId: string): Promise<TaskPreviewResult> =>
      requestJson<TaskPreviewResult>({ method: 'POST', url: `/api/task/${taskId}/preview/rebuild` }),
    download: (taskId: string) => requestBlob({ method: 'GET', url: `/api/task/${taskId}/download` }),
    modify: (taskId: string, payload: { changeInstructions: string }) =>
      requestJson<GenerateResult>({
        url: `/api/task/${taskId}/modify`,
        method: 'POST',
        data: payload
      }),
    cancel: (taskId: string) =>
      requestJson<GenerateResult>({
        url: `/api/task/${taskId}/cancel`,
        method: 'POST'
      }),
    retry: (taskId: string, stepCode: string) =>
      requestJson<GenerateResult>({
        url: `/api/task/${taskId}/retry/${stepCode}`,
        method: 'POST'
      }),
    logs: (taskId: string) =>
      requestJson<TaskLogResult[]>({
        url: `/api/task/${taskId}/logs`,
        method: 'GET'
      }),
    previewLogs: (taskId: string, tail = 200) =>
      requestJson<PreviewLogsResult>({
        url: `/api/task/${taskId}/preview/logs`,
        method: 'GET',
        params: { tail }
      }),
  }

  const billingApi = {
    getBalance: () => requestJson<BalanceResult>({ method: 'GET', url: '/api/billing/balance' }),
    getRecords: () => requestJson<BillingRecordResult[]>({ method: 'GET', url: '/api/billing/records' }),
    createRechargeOrder: (payload: { amount: number; quota: number; payChannel: string }) =>
      requestJson<RechargeOrderResult>({ method: 'POST', url: '/api/billing/recharge/orders', data: payload }),
    getRechargeOrder: (orderId: string) =>
      requestJson<RechargeOrderResult>({ method: 'GET', url: `/api/billing/recharge/orders/${orderId}` }),
    callbackRecharge: (payload: { orderId: string; paymentNo: string; signature: string; paidAmount?: number; payChannel?: string }) =>
      requestJson<RechargeOrderResult>({ method: 'POST', url: '/api/billing/recharge/callback', data: payload }),
  }

  const paperApi = {
    generateOutline: (payload: PaperOutlineGenerateRequest) =>
      requestJson<PaperOutlineGenerateResult>({ method: 'POST', url: '/api/paper/outline', data: payload }),
    suggestTopics: (payload: TopicSuggestRequest) =>
      requestJson<TopicSuggestResult>({ method: 'POST', url: '/api/paper/topic/suggest', data: payload }),
    adoptTopic: (payload: TopicAdoptRequest) =>
      requestJson<PaperOutlineGenerateRequest>({ method: 'POST', url: '/api/paper/topic/adopt', data: payload }),
    getOutline: (taskId: string) => requestJson<PaperOutlineResult>({ method: 'GET', url: `/api/paper/outline/${taskId}` }),
    getManuscript: (taskId: string) => requestJson<PaperManuscriptResult>({ method: 'GET', url: `/api/paper/manuscript/${taskId}` }),
    getTraceability: (taskId: string) =>
      requestJson<PaperTraceabilityResult>({ method: 'GET', url: `/api/paper/traceability/${taskId}` }),
    getRagRetrieval: (taskId: string, reranked = false) =>
      requestJson<RagRetrievalResult>({ method: 'GET', url: `/api/paper/rag/retrieval/${taskId}`, params: { reranked } }),
    list: () => requestJson<PaperProjectSummary[]>({ method: 'GET', url: '/api/paper/list' }),
  }

  return {
    authApi,
    userApi,
    chatApi,
    projectApi,
    taskApi,
    billingApi,
    paperApi,
  }
}
