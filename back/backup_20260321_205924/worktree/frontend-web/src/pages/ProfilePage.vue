<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { userApi } from '@/api/endpoints'
import { showApiError } from '@/api/http'
import { useBillingStore } from '@/stores/billing'
import BillingRecordList from '@/components/BillingRecordList.vue'
import type { UserProfileResult } from '@/types/api'

const billingStore = useBillingStore()
const loading = ref(false)
const profile = ref<UserProfileResult | null>(null)

const refresh = async () => {
  loading.value = true
  try {
    const [profileRes] = await Promise.all([
      userApi.profile(),
      billingStore.refresh(),
    ])
    profile.value = profileRes
  } catch (e) {
    showApiError(e)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void refresh()
})
</script>

<template>
  <div class="mx-auto max-w-5xl space-y-6">
    <div class="flex items-center justify-between">
      <div>
        <div class="text-2xl font-semibold">个人中心</div>
        <div class="mt-1 text-sm text-slate-500 dark:text-slate-400">查看个人资料、积分余额和账单明细</div>
      </div>
      <button
        type="button"
        class="rounded-lg border px-4 py-2 text-sm hover:bg-slate-50 dark:border-slate-800 dark:hover:bg-slate-900"
        @click="refresh"
      >
        刷新
      </button>
    </div>

    <div class="grid grid-cols-1 gap-4 md:grid-cols-3">
      <div class="rounded-2xl border bg-white p-5 shadow-sm dark:border-slate-900 dark:bg-slate-950">
        <div class="text-sm text-slate-500 dark:text-slate-400">用户名</div>
        <div class="mt-2 text-lg font-semibold">{{ profile?.username || '--' }}</div>
      </div>
      <div class="rounded-2xl border bg-white p-5 shadow-sm dark:border-slate-900 dark:bg-slate-950">
        <div class="text-sm text-slate-500 dark:text-slate-400">用户 ID</div>
        <div class="mt-2 text-lg font-semibold">{{ profile?.userId ?? '--' }}</div>
      </div>
      <div class="rounded-2xl border bg-white p-5 shadow-sm dark:border-slate-900 dark:bg-slate-950">
        <div class="text-sm text-slate-500 dark:text-slate-400">注册时间</div>
        <div class="mt-2 text-sm font-medium">{{ profile?.createdAt ? new Date(profile.createdAt).toLocaleString() : '--' }}</div>
      </div>
    </div>

    <div class="grid grid-cols-1 gap-4 md:grid-cols-2">
      <div class="rounded-2xl border bg-white p-5 shadow-sm dark:border-slate-900 dark:bg-slate-950">
        <div class="text-sm text-slate-500 dark:text-slate-400">账户余额</div>
        <div class="mt-2 text-2xl font-semibold">{{ billingStore.balance }}</div>
      </div>
      <div class="rounded-2xl border bg-white p-5 shadow-sm dark:border-slate-900 dark:bg-slate-950">
        <div class="text-sm text-slate-500 dark:text-slate-400">积分余额</div>
        <div class="mt-2 text-2xl font-semibold">{{ billingStore.quota }}</div>
      </div>
    </div>

    <BillingRecordList :records="billingStore.records" />

    <div v-if="loading" class="text-sm text-slate-500 dark:text-slate-400">
      正在加载数据...
    </div>
  </div>
</template>
