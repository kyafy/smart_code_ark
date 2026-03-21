import { defineStore } from 'pinia'
import { ref } from 'vue'
import { billingApi } from '@/api/endpoints'
import type { BillingRecordResult } from '@smartark/domain/api'

export const useBillingStore = defineStore('mobile-billing', () => {
  const balance = ref(0)
  const quota = ref(0)
  const records = ref<BillingRecordResult[]>([])

  const refresh = async () => {
    const [balanceRes, recordsRes] = await Promise.all([billingApi.getBalance(), billingApi.getRecords()])
    balance.value = Number(balanceRes.balance ?? 0)
    quota.value = Number(balanceRes.quota ?? 0)
    records.value = recordsRes
  }

  return { balance, quota, records, refresh }
})
