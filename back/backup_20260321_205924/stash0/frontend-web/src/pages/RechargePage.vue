<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { billingApi } from '@/api/endpoints'
import { ApiRequestError, showApiError } from '@/api/http'
import { useBillingStore } from '@/stores/billing'
import type { RechargeOrderResult } from '@/types/api'

const billingStore = useBillingStore()
const creating = ref(false)
const activeOrder = ref<RechargeOrderResult | null>(null)
const selectedPlan = ref('p2')
let timer: ReturnType<typeof setInterval> | null = null

const plans = [
  { id: 'p1', title: '基础包', amount: 9.9, quota: 100 },
  { id: 'p2', title: '进阶包', amount: 29.9, quota: 350 },
  { id: 'p3', title: '旗舰包', amount: 99.9, quota: 1300 },
]

const currentPlan = computed(() => plans.find((p) => p.id === selectedPlan.value) ?? plans[1])
const signSecret = import.meta.env.VITE_RECHARGE_CALLBACK_MOCK_SECRET ?? 'smartark-recharge-secret'

const stopPolling = () => {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

const refreshOrderStatus = async () => {
  if (!activeOrder.value?.orderId) return
  try {
    const latest = await billingApi.getRechargeOrder(activeOrder.value.orderId)
    activeOrder.value = latest
    if (latest.status === 'paid') {
      stopPolling()
      await billingStore.refresh()
      ElMessage.success('充值成功，余额和积分已更新')
    }
  } catch (e) {
    if (e instanceof ApiRequestError && (e.code === 1004 || e.httpStatus === 404)) {
      stopPolling()
      ElMessage.warning('订单不存在或已关闭')
      return
    }
    showApiError(e)
  }
}

const startPolling = () => {
  stopPolling()
  timer = setInterval(() => {
    void refreshOrderStatus()
  }, 2500)
}

const createOrder = async () => {
  creating.value = true
  try {
    const order = await billingApi.createRechargeOrder({
      amount: currentPlan.value.amount,
      quota: currentPlan.value.quota,
      payChannel: 'mock',
    })
    activeOrder.value = order
    ElMessage.success('订单创建成功，请完成支付')
    startPolling()
  } catch (e) {
    showApiError(e)
  } finally {
    creating.value = false
  }
}

const mockPaySuccess = async () => {
  if (!activeOrder.value?.orderId) return
  try {
    const paymentNo = `pay_${Date.now()}`
    const signature = `${activeOrder.value.orderId}|${paymentNo}|${signSecret}`
    await billingApi.callbackRecharge({
      orderId: activeOrder.value.orderId,
      paymentNo,
      signature,
      paidAmount: activeOrder.value.amount,
      payChannel: 'mock',
    })
    await refreshOrderStatus()
  } catch (e) {
    showApiError(e)
  }
}

onMounted(() => {
  void billingStore.refresh()
})

onBeforeUnmount(() => {
  stopPolling()
})
</script>

<template>
  <div class="mx-auto max-w-5xl space-y-6">
    <div>
      <div class="text-2xl font-semibold">积分充值</div>
      <div class="mt-1 text-sm text-slate-500 dark:text-slate-400">选择套餐并创建订单，支付完成后自动刷新积分余额</div>
    </div>

    <div class="rounded-2xl border bg-white p-5 shadow-sm dark:border-slate-900 dark:bg-slate-950">
      <div class="mb-3 text-base font-semibold">选择套餐</div>
      <div class="grid grid-cols-1 gap-3 md:grid-cols-3">
        <button
          v-for="p in plans"
          :key="p.id"
          type="button"
          class="rounded-xl border p-4 text-left transition"
          :class="selectedPlan === p.id ? 'border-blue-600 bg-blue-50 dark:border-blue-400 dark:bg-blue-950/30' : 'dark:border-slate-800 hover:border-blue-300'"
          @click="selectedPlan = p.id"
        >
          <div class="text-sm font-semibold">{{ p.title }}</div>
          <div class="mt-2 text-2xl font-bold">¥{{ p.amount }}</div>
          <div class="mt-1 text-xs text-slate-500 dark:text-slate-400">获得 {{ p.quota }} 积分</div>
        </button>
      </div>
      <div class="mt-4">
        <button
          type="button"
          class="rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white hover:bg-blue-700 disabled:opacity-60"
          :disabled="creating"
          @click="createOrder"
        >
          {{ creating ? '创建中...' : '创建充值订单' }}
        </button>
      </div>
    </div>

    <div class="rounded-2xl border bg-white p-5 shadow-sm dark:border-slate-900 dark:bg-slate-950">
      <div class="mb-2 text-base font-semibold">当前订单</div>
      <div v-if="!activeOrder" class="text-sm text-slate-500 dark:text-slate-400">
        暂无订单，请先选择套餐并创建订单。
      </div>
      <div v-else class="space-y-3 text-sm">
        <div>订单号：{{ activeOrder.orderId }}</div>
        <div>状态：<span class="font-semibold">{{ activeOrder.status }}</span></div>
        <div>金额：¥{{ activeOrder.amount }}</div>
        <div>积分：{{ activeOrder.quota }}</div>
        <div class="flex flex-wrap gap-2">
          <button
            type="button"
            class="rounded-lg border px-3 py-1.5 hover:bg-slate-50 dark:border-slate-800 dark:hover:bg-slate-900"
            @click="refreshOrderStatus"
          >
            刷新订单状态
          </button>
          <button
            v-if="activeOrder.status !== 'paid'"
            type="button"
            class="rounded-lg bg-emerald-600 px-3 py-1.5 text-white hover:bg-emerald-700"
            @click="mockPaySuccess"
          >
            模拟支付成功
          </button>
        </div>
      </div>
    </div>

    <div class="rounded-2xl border bg-white p-5 shadow-sm dark:border-slate-900 dark:bg-slate-950">
      <div class="text-sm text-slate-500 dark:text-slate-400">当前积分余额</div>
      <div class="mt-2 text-2xl font-semibold">{{ billingStore.quota }}</div>
    </div>
  </div>
</template>
