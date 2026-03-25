<script setup lang="ts">
import { ref } from 'vue'
import { taskApi } from '@/api/endpoints'
import type { TaskStatusResult } from '@smartark/domain/api'

const taskId = ref('')
const status = ref<TaskStatusResult | null>(null)

const query = async () => {
  if (!taskId.value.trim()) return
  try {
    status.value = await taskApi.status(taskId.value.trim())
  } catch {
    uni.showToast({ title: '查询失败', icon: 'none' })
  }
}
</script>

<template>
  <view class="page">
    <view class="title">任务进度</view>
    <input v-model="taskId" class="input" placeholder="输入任务 ID" />
    <button @click="query">查询状态</button>
    <view v-if="status" class="card">
      <view>状态：{{ status.status }}</view>
      <view>进度：{{ status.progress }}%</view>
      <view>步骤：{{ status.current_step || status.step }}</view>
      <view v-if="status.errorMessage">错误：{{ status.errorMessage }}</view>
    </view>
  </view>
</template>

<style scoped>
.page { padding: 24rpx; display: flex; flex-direction: column; gap: 14rpx; }
.title { font-size: 34rpx; font-weight: 600; }
.input { background: #fff; border: 1rpx solid #e2e8f0; border-radius: 12rpx; padding: 14rpx; }
.card { background: #fff; border: 1rpx solid #e2e8f0; border-radius: 12rpx; padding: 16rpx; display: flex; flex-direction: column; gap: 8rpx; }
</style>
