<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { billingApi } from '@/api/endpoints'
import { useBillingStore } from '@/stores/billing'
import type { RechargeOrderResult } from '@smartark/domain/api'

const billing = useBillingStore()
const activePlan = ref('p2')
const order = ref<RechargeOrderResult | null>(null)
const plans = [
  { id: 'p1', amount: 9.9, quota: 100 },
  { id: 'p2', amount: 29.9, quota: 350 },
  { id: 'p3', amount: 99.9, quota: 1300 },
]
const currentPlan = computed(() => plans.find((p) => p.id === activePlan.value) || plans[1])
const polling = ref(false)
let timer: ReturnType<typeof setInterval> | null = null

const stopPolling = () => {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
  polling.value = false
}

const startPolling = () => {
  stopPolling()
  polling.value = true
  timer = setInterval(() => {
    void refreshOrder()
  }, 2000)
}

const createOrder = async () => {
  order.value = await billingApi.createRechargeOrder({
    amount: currentPlan.value.amount,
    quota: currentPlan.value.quota,
    payChannel: 'mock',
  })
  uni.showToast({ title: '订单已创建', icon: 'success' })
  startPolling()
}

const refreshOrder = async () => {
  if (!order.value?.orderId) return
  order.value = await billingApi.getRechargeOrder(order.value.orderId)
  if (order.value.status === 'paid') {
    stopPolling()
    await billing.refresh()
    uni.showToast({ title: '充值已到账', icon: 'success' })
  }
}

onMounted(() => {
  void billing.refresh()
})

onBeforeUnmount(() => {
  stopPolling()
})
</script>

<template>
  <view class="page">
    <view class="title">积分充值</view>
    <view class="plans">
      <view v-for="p in plans" :key="p.id" class="plan" :class="{ active: activePlan === p.id }" @click="activePlan = p.id">
        <view>￥{{ p.amount }}</view>
        <view>{{ p.quota }} 积分</view>
      </view>
    </view>
    <button @click="createOrder">创建订单</button>
    <view v-if="order" class="card">
      <view>订单号：{{ order.orderId }}</view>
      <view>状态：{{ order.status }}</view>
      <view v-if="polling && order.status !== 'paid'">支付状态轮询中...</view>
      <button size="mini" @click="refreshOrder">刷新订单状态</button>
    </view>
    <view class="card">当前积分：{{ billing.quota }}</view>
  </view>
</template>

<style scoped>
.page { padding: 24rpx; display: flex; flex-direction: column; gap: 14rpx; }
.title { font-size: 34rpx; font-weight: 600; }
.plans { display: flex; gap: 10rpx; }
.plan { flex: 1; background: #fff; border: 1rpx solid #e2e8f0; border-radius: 12rpx; padding: 12rpx; text-align: center; }
.plan.active { border-color: #2563eb; background: #eff6ff; }
.card { background: #fff; border: 1rpx solid #e2e8f0; border-radius: 12rpx; padding: 14rpx; display: flex; flex-direction: column; gap: 8rpx; }
</style>
