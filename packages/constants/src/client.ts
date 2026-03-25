export const CLIENT_HEADERS = {
  platform: 'X-Client-Platform',
  appVersion: 'X-App-Version',
  deviceId: 'X-Device-Id',
} as const

export type ClientPlatform = 'web' | 'miniprogram' | 'app' | 'unknown'

export const DEFAULT_CLIENT_PLATFORM: ClientPlatform = 'web'
