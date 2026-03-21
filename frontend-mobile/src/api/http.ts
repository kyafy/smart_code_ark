import { createApiSdk } from '@smartark/api-sdk'
import { CLIENT_HEADERS } from '@smartark/constants'
import type { ApiResponse } from '@smartark/domain/api'

export class ApiRequestError extends Error {
  code: number
  httpStatus?: number

  constructor(message: string, code: number, httpStatus?: number) {
    super(message)
    this.code = code
    this.httpStatus = httpStatus
  }
}

const baseURL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'

const getToken = () => uni.getStorageSync('smartark_token') as string | undefined

const getDeviceId = () => {
  let deviceId = uni.getStorageSync('smartark_device_id') as string | undefined
  if (!deviceId) {
    deviceId = `${Date.now()}_${Math.random().toString(36).slice(2, 10)}`
    uni.setStorageSync('smartark_device_id', deviceId)
  }
  return deviceId
}

const getHeaders = () => {
  const headers: Record<string, string> = {
    [CLIENT_HEADERS.platform]: 'miniprogram',
    [CLIENT_HEADERS.appVersion]: import.meta.env.VITE_APP_VERSION ?? '3.0.0',
    [CLIENT_HEADERS.deviceId]: getDeviceId(),
  }
  const token = getToken()
  if (token) headers.Authorization = `Bearer ${token}`
  return headers
}

const unwrap = <T>(payload: ApiResponse<T>, httpStatus?: number): T => {
  if (payload.code === 0) return payload.data
  throw new ApiRequestError(payload.message || '请求失败', payload.code, httpStatus)
}

type RequestConfig = { method: string; url: string; data?: unknown; params?: unknown }

export const requestJson = <T>(config: RequestConfig): Promise<T> =>
  new Promise((resolve, reject) => {
    uni.request({
      url: `${baseURL}${config.url}`,
      method: config.method as any,
      data: config.data ?? config.params,
      header: getHeaders(),
      success: (resp) => {
        try {
          const data = resp.data as ApiResponse<T>
          resolve(unwrap(data, resp.statusCode))
        } catch (e) {
          reject(e)
        }
      },
      fail: (e) => reject(e),
    })
  })

export const requestBlob = async () => {
  throw new ApiRequestError('移动端 MVP 暂不支持二进制下载', 9000)
}

export const requestSse = async (
  url: string,
  body: any,
  onMessage: (event: string, data: any) => void,
  onError: (err: any) => void
) => {
  try {
    const response = await fetch(`${baseURL}${url}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...getHeaders(),
      },
      body: JSON.stringify(body),
    })
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    const text = await response.text()
    const lines = text.split('\n')
    let currentEvent = 'message'
    for (const rawLine of lines) {
      const line = rawLine.trim()
      if (!line) continue
      if (line.startsWith('event:')) {
        currentEvent = line.slice(6).trim()
      } else if (line.startsWith('data:')) {
        const dataStr = line.slice(5).trim()
        let parsed: any = dataStr
        try {
          parsed = JSON.parse(dataStr)
        } catch {}
        onMessage(currentEvent, parsed)
        currentEvent = 'message'
      }
    }
  } catch (err) {
    onError(err)
  }
}

export const mobileApi = createApiSdk({
  requestJson,
  requestBlob: requestBlob as any,
  requestSse,
})
