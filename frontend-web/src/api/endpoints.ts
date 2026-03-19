import { requestBlob, requestJson, requestSse } from '@/api/http'
import type {
  AuthLoginResult,
  ChatReplyResult,
  ChatStartResult,
  GenerateResult,
  ProjectConfirmResult,
  ProjectSummary,
  ProjectDetail,
  SmsSendResult,
  StackConfig,
  TaskLogResult,
  TaskPreviewResult,
  TaskStatusResult,
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
  status: (taskId: string) => requestJson<TaskStatusResult>({ method: 'GET', url: `/task/${taskId}/status` }),
  preview: (taskId: string) => requestJson<TaskPreviewResult>({ method: 'GET', url: `/task/${taskId}/preview` }),
  download: (taskId: string) => requestBlob({ method: 'GET', url: `/task/${taskId}/download` }),
  modify: (taskId: string, payload: { changeInstructions: string }) =>
    requestJson<GenerateResult>({
      url: `/task/${taskId}/modify`,
      method: 'POST',
      data: payload
    }),
  cancel: (taskId: string) =>
    requestJson<GenerateResult>({
      url: `/task/${taskId}/cancel`,
      method: 'POST'
    }),
  retry: (taskId: string, stepCode: string) =>
    requestJson<GenerateResult>({
      url: `/task/${taskId}/retry/${stepCode}`,
      method: 'POST'
    }),
  logs: (taskId: string) =>
    requestJson<TaskLogResult[]>({
      url: `/task/${taskId}/logs`,
      method: 'GET'
    })
}
