import { requestBlob, requestJson, requestSse } from '@/api/http'
import { createApiSdk } from '@smartark/api-sdk'

const sdk = createApiSdk({ requestBlob, requestJson, requestSse })

export const authApi = sdk.authApi
export const userApi = sdk.userApi
export const chatApi = sdk.chatApi
export const projectApi = sdk.projectApi
export const taskApi = sdk.taskApi
export const billingApi = sdk.billingApi
export const paperApi = sdk.paperApi
