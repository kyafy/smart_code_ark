import { defineStore } from 'pinia'
import { ref } from 'vue'
import { authApi } from '@/api/endpoints'

export const useAuthStore = defineStore('mobile-auth', () => {
  const token = ref<string>(uni.getStorageSync('smartark_token') || '')
  const userId = ref<string>(uni.getStorageSync('smartark_user_id') || '')

  const login = async (username: string, password: string) => {
    const res = await authApi.login({ username, password })
    token.value = String(res.token)
    userId.value = String(res.userId)
    uni.setStorageSync('smartark_token', token.value)
    uni.setStorageSync('smartark_user_id', userId.value)
  }

  const logout = () => {
    token.value = ''
    userId.value = ''
    uni.removeStorageSync('smartark_token')
    uni.removeStorageSync('smartark_user_id')
  }

  return { token, userId, login, logout }
})
