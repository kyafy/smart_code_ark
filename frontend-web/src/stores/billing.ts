import { defineStore } from 'pinia'
import { ref } from 'vue'
import { billingApi } from '@/api/endpoints'
import type { BillingRecordResult } from '@/types/api'

export const useBillingStore = defineStore('billing', () => {
  const balance = ref<number>(0)
  const quota = ref<number>(0)
  const records = ref<BillingRecordResult[]>([])
  const loading = ref(false)

  const refreshBalance = async () => {
    try {
      const res = await billingApi.getBalance()
      balance.value = Number(res.balance ?? 0)
      quota.value = Number(res.quota ?? 0)
    } catch (e) {
      console.error('Failed to load balance', e)
    }
  }

  const refreshRecords = async () => {
    try {
      records.value = await billingApi.getRecords()
    } catch (e) {
      console.error('Failed to load records', e)
      records.value = []
    }
  }

  const refresh = async () => {
    loading.value = true
    try {
      await Promise.all([refreshBalance(), refreshRecords()])
    } finally {
      loading.value = false
    }
  }

  return {
    balance,
    quota,
    records,
    loading,
    refreshBalance,
    refreshRecords,
    refresh,
  }
})

