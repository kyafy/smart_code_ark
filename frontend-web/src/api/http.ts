import axios, { AxiosError, type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import { handleMock } from '@/api/mock'
import type { ApiResponse } from '@/types/api'
import { useAuthStore } from '@/stores/auth'
import { CLIENT_HEADERS, DEFAULT_CLIENT_PLATFORM } from '@smartark/constants'

export class ApiRequestError extends Error {
  code: number
  httpStatus?: number

  constructor(message: string, code: number, httpStatus?: number) {
    super(message)
    this.code = code
    this.httpStatus = httpStatus
  }
}

const useMock = (import.meta.env.VITE_USE_MOCK ?? 'true') === 'true'

const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '',
  timeout: 30_000,
})

const getClientMetaHeaders = () => {
  const appVersion = import.meta.env.VITE_APP_VERSION ?? '3.0.0'
  const deviceId = localStorage.getItem('__smartark_device_id__') || crypto.randomUUID()
  localStorage.setItem('__smartark_device_id__', deviceId)
  return {
    [CLIENT_HEADERS.platform]: DEFAULT_CLIENT_PLATFORM,
    [CLIENT_HEADERS.appVersion]: appVersion,
    [CLIENT_HEADERS.deviceId]: deviceId,
  }
}

api.interceptors.request.use((config) => {
  const auth = useAuthStore()
  const token = auth.token
  if (token) {
    config.headers = {
      ...(config.headers as any),
      ...getClientMetaHeaders(),
      Authorization: `Bearer ${token}`,
    }
  } else {
    config.headers = {
      ...(config.headers as any),
      ...getClientMetaHeaders(),
    }
  }
  return config
})

api.interceptors.response.use(
  (resp) => resp,
  (err: AxiosError) => {
    const status = err.response?.status
    if (status === 401) {
      const auth = useAuthStore()
      auth.logout()
      ElMessage.error('登录已过期，请重新登录')
      window.location.href = '/login'
      return Promise.reject(err)
    }
    return Promise.reject(err)
  },
)

const unwrap = <T>(payload: ApiResponse<T>, httpStatus?: number): T => {
  if (payload.code === 0) return payload.data
  throw new ApiRequestError(payload.message || '请求失败', payload.code, httpStatus)
}

export const requestJson = async <T>(config: AxiosRequestConfig): Promise<T> => {
  if (useMock) {
    const method = (config.method ?? 'GET').toString().toUpperCase()
    const url = String(config.url ?? '')
    const out = await handleMock({
      method: (['GET', 'POST', 'DELETE'].includes(method) ? method : 'GET') as 'GET' | 'POST' | 'DELETE',
      url,
      body: config.data,
      params: (config.params ?? undefined) as any,
    })
    if (out instanceof Blob) {
      throw new ApiRequestError('预期 JSON 响应但返回了二进制', 9000)
    }
    return unwrap(out as ApiResponse<T>)
  }

  const resp = await api.request<ApiResponse<T>>(config)
  return unwrap(resp.data, resp.status)
}

export const requestBlob = async (config: AxiosRequestConfig): Promise<Blob> => {
  if (useMock) {
    const method = (config.method ?? 'GET').toString().toUpperCase()
    const url = String(config.url ?? '')
    const out = await handleMock({
      method: (['GET', 'POST', 'DELETE'].includes(method) ? method : 'GET') as 'GET' | 'POST' | 'DELETE',
      url,
      body: config.data,
      params: (config.params ?? undefined) as any,
    })
    if (out instanceof Blob) return out
    throw new ApiRequestError('预期二进制响应但返回了 JSON', 9000)
  }

  const resp = await api.request<Blob>({ ...config, responseType: 'blob' })
  return resp.data
}

export const requestSse = async (
  url: string,
  body: any,
  onMessage: (event: string, data: any) => void,
  onError: (err: any) => void
) => {
  const auth = useAuthStore()
  const token = auth.token
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...getClientMetaHeaders(),
  }
  if (token) {
    headers['Authorization'] = `Bearer ${token}`
  }

  try {
    const fetchUrl = (import.meta.env.VITE_API_BASE_URL ?? '') + url
    const response = await fetch(fetchUrl, {
      method: 'POST',
      headers,
      body: JSON.stringify(body),
    })

    if (!response.ok) {
      if (response.status === 401) {
        auth.logout()
        window.location.href = '/login'
        return
      }
      throw new Error(`HTTP error! status: ${response.status}`)
    }

    const reader = response.body?.getReader()
    if (!reader) throw new Error('No response body')

    const decoder = new TextDecoder()
    let buffer = ''
    let currentEvent = 'message'

    for (;;) {
      const { done, value } = await reader.read()
      
      if (value) {
        buffer += decoder.decode(value, { stream: true })
      }
      
      const lines = buffer.split('\n')
      // Keep the last partial line in buffer unless we are done
      const pending = done ? '' : (lines.pop() || '')
      
      for (const line of lines) {
        const trimmed = line.trim()
        if (!trimmed) continue

        if (trimmed.startsWith('event:')) {
          currentEvent = trimmed.substring(6).trim()
        } else if (trimmed.startsWith('data:')) {
          const dataStr = trimmed.substring(5).trim()
          const parsedData: any = (() => {
            const t = dataStr.trim()
            const looksJson =
              (t.startsWith('{') && t.endsWith('}')) ||
              (t.startsWith('[') && t.endsWith(']'))
            if (!looksJson) return dataStr
            try {
              return JSON.parse(t)
            } catch {
              return dataStr
            }
          })()

          if (currentEvent === 'error') {
            const code = typeof parsedData?.code === 'number' ? parsedData.code : 5000
            const msg = typeof parsedData?.message === 'string' ? parsedData.message : '请求失败'
            throw new ApiRequestError(msg, code)
          }

          onMessage(currentEvent, parsedData)
          // Reset event name to default
          currentEvent = 'message'
        }
      }
      buffer = pending

      if (done) break
    }
  } catch (e) {
    onError(e)
  }
}

export const showApiError = (err: unknown) => {
  if (err instanceof ApiRequestError) {
    if (err.code === 2001 || err.httpStatus === 402) {
      ElMessage.warning('余额/积分不足')
      return
    }
    if (err.code === 3106 || err.httpStatus === 429) {
      ElMessage.warning('预览并发数已达上限，请关闭其他预览后重试')
      return
    }
    ElMessage.error(err.message)
    return
  }

  if (err instanceof AxiosError) {
    ElMessage.error(err.message)
    return
  }

  ElMessage.error('请求失败')
}
