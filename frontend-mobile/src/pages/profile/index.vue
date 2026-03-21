<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { userApi } from '@/api/endpoints'
import { useBillingStore } from '@/stores/billing'
import type { UserProfileResult } from '@smartark/domain/api'

const billing = useBillingStore()
const profile = ref<UserProfileResult | null>(null)

const load = async () => {
  const [p] = await Promise.all([userApi.profile(), billing.refresh()])
  profile.value = p
}

onMounted(() => {
  void load()
})
</script>

<template>
  <view class="page">
    <view class="title">个人中心</view>
    <view class="card">用户名：{{ profile?.username || '--' }}</view>
    <view class="card">用户ID：{{ profile?.userId ?? '--' }}</view>
    <view class="card">余额：{{ billing.balance }}</view>
    <view class="card">积分：{{ billing.quota }}</view>
    <view class="subTitle">账单记录</view>
    <view v-for="item in billing.records" :key="item.id" class="record">
      <view>{{ item.reason }}</view>
      <view>{{ item.changeAmount }}</view>
      <view class="time">{{ item.createdAt }}</view>
    </view>
  </view>
</template>

<style scoped>
.page { padding: 24rpx; display: flex; flex-direction: column; gap: 12rpx; }
.title { font-size: 34rpx; font-weight: 600; margin-bottom: 6rpx; }
.subTitle { font-size: 28rpx; font-weight: 600; margin-top: 8rpx; }
.card, .record { background: #fff; border: 1rpx solid #e2e8f0; border-radius: 12rpx; padding: 14rpx; }
.time { color: #64748b; margin-top: 6rpx; font-size: 22rpx; }
</style>
