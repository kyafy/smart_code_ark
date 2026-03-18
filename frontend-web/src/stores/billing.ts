import { defineStore } from 'pinia'
import { ref } from 'vue'
import { requestJson } from '@/api/http'
import type { ApiResponse } from '@/types/api'

export const useBillingStore = defineStore('billing', () => {
  const balance = ref<number | null>(null)

  const refresh = async () => {
    try {
      const res = await requestJson<{ balance: number; quota: number }>({
        method: 'GET',
        url: '/api/billing/balance',
      })
      balance.value = res.quota
    } catch (e) {
      console.error('Failed to load balance', e)
    }
  }

  return {
    balance,
    refresh,
  }
})

