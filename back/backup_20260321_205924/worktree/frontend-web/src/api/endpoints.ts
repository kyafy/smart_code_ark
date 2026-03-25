import { requestBlob, requestJson, requestSse } from '@/api/http'
<<<<<<< HEAD
import type {
  AuthLoginResult,
  BalanceResult,
  BillingRecordResult,
  ChatReplyResult,
  ChatStartResult,
  GenerateResult,
  PaperOutlineGenerateRequest,
  PaperOutlineGenerateResult,
  PaperManuscriptResult,
  PaperOutlineResult,
  PaperProjectSummary,
  ProjectConfirmResult,
  ProjectSummary,
  RechargeOrderResult,
  ProjectDetail,
  SmsSendResult,
  StackConfig,
  TaskLogResult,
  TaskPreviewResult,
  TaskStatusResult,
  UserProfileResult,
} from '@/types/api'

export const authApi = {
  register: (payload: { username: string; password: string; phone?: string }) =>
    requestJson<{ userId: string | number }>({ method: 'POST', url: '/api/auth/register', data: payload }),
  login: (payload: { username: string; password: string }) =>
    requestJson<AuthLoginResult>({ method: 'POST', url: '/api/auth/login', data: payload }),
  smsSend: (payload: { phone: string; scene: 'login' | string }) =>
    requestJson<SmsSendResult>({ method: 'POST', url: '/api/auth/sms/send', data: payload }),
  loginSms: (payload: { phone: string; captcha: string }) =>
    requestJson<AuthLoginResult>({ method: 'POST', url: '/api/auth/login/sms', data: payload }),
}

export const userApi = {
  profile: () => requestJson<UserProfileResult>({ method: 'GET', url: '/api/user/profile' }),
}

export const chatApi = {
  start: (payload: { title: string; projectType: string; description?: string }) =>
    requestJson<ChatStartResult>({ method: 'POST', url: '/api/chat/start', data: payload }),
  send: (
    payload: { sessionId: string; message: string },
    onMessage: (event: string, data: any) => void
  ) =>
    requestSse('/api/chat', payload, onMessage, (err) => {
      throw err
    }),
  getSessions: () => requestJson<any[]>({ method: 'GET', url: '/api/chat/sessions' }),
  getSessionMessages: (sessionId: string) =>
    requestJson<ChatReplyResult>({ method: 'GET', url: `/api/chat/sessions/${sessionId}/messages` }),
  deleteSession: (sessionId: string) =>
    requestJson<boolean>({ method: 'DELETE', url: `/api/chat/sessions/${sessionId}` }),
}

export const projectApi = {
  list: () => requestJson<ProjectSummary[]>({ method: 'GET', url: '/api/projects' }),
  detail: (id: string) => requestJson<ProjectDetail>({ method: 'GET', url: `/api/projects/${id}` }),
  confirm: (payload: { sessionId: string; stack: StackConfig; description?: string; prd?: string }) =>
    requestJson<ProjectConfirmResult>({ method: 'POST', url: '/api/projects/confirm', data: payload }),
}

export const taskApi = {
  generate: (payload: { projectId: string; instructions?: string }) =>
    requestJson<GenerateResult>({ method: 'POST', url: '/api/generate', data: payload }),
  status: (taskId: string) => requestJson<TaskStatusResult>({ method: 'GET', url: `/api/task/${taskId}/status` }),
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
    })
}

export const billingApi = {
  getBalance: () => requestJson<BalanceResult>({ method: 'GET', url: '/api/billing/balance' }),
  getRecords: () => requestJson<BillingRecordResult[]>({ method: 'GET', url: '/api/billing/records' }),
  createRechargeOrder: (payload: { amount: number; quota: number; payChannel: string }) =>
    requestJson<RechargeOrderResult>({ method: 'POST', url: '/api/billing/recharge/orders', data: payload }),
  getRechargeOrder: (orderId: string) =>
    requestJson<RechargeOrderResult>({ method: 'GET', url: `/api/billing/recharge/orders/${orderId}` }),
  callbackRecharge: (payload: { orderId: string; paymentNo: string; signature: string; paidAmount?: number; payChannel?: string }) =>
    requestJson<RechargeOrderResult>({ method: 'POST', url: '/api/billing/recharge/callback', data: payload }),
}

export const paperApi = {
  generateOutline: (payload: PaperOutlineGenerateRequest) =>
    requestJson<PaperOutlineGenerateResult>({ method: 'POST', url: '/api/paper/outline', data: payload }),
  getOutline: (taskId: string) => requestJson<PaperOutlineResult>({ method: 'GET', url: `/api/paper/outline/${taskId}` }),
  getManuscript: (taskId: string) => requestJson<PaperManuscriptResult>({ method: 'GET', url: `/api/paper/manuscript/${taskId}` }),
  list: () => requestJson<PaperProjectSummary[]>({ method: 'GET', url: '/api/paper/list' }),
}
=======
import { createApiSdk } from '@smartark/api-sdk'

const sdk = createApiSdk({ requestBlob, requestJson, requestSse })

export const authApi = sdk.authApi
export const userApi = sdk.userApi
export const chatApi = sdk.chatApi
export const projectApi = sdk.projectApi
export const taskApi = sdk.taskApi
export const billingApi = sdk.billingApi
export const paperApi = sdk.paperApi
>>>>>>> origin/master
