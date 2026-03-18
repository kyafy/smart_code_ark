import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { authApi } from '@/api/endpoints'

const TOKEN_KEY = 'smartark_token'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string>(localStorage.getItem(TOKEN_KEY) ?? '')
  const userId = ref<string | number>('')

  const isAuthed = computed(() => Boolean(token.value))

  const setToken = (next: string) => {
    token.value = next
    localStorage.setItem(TOKEN_KEY, next)
  }

  const logout = () => {
    token.value = ''
    userId.value = ''
    localStorage.removeItem(TOKEN_KEY)
  }

  const login = async (payload: { username: string; password: string }) => {
    const res = await authApi.login(payload)
    setToken(res.token)
    userId.value = res.userId
  }

  const smsSend = async (payload: { phone: string }) => {
    await authApi.smsSend({ phone: payload.phone, scene: 'login' })
  }

  const loginSms = async (payload: { phone: string; captcha: string }) => {
    const res = await authApi.loginSms(payload)
    setToken(res.token)
    userId.value = res.userId
  }

  const register = async (payload: { username: string; password: string; phone?: string }) => {
    const res = await authApi.register(payload)
    userId.value = res.userId
  }

  return {
    token,
    userId,
    isAuthed,
    login,
    smsSend,
    loginSms,
    register,
    logout,
  }
})

