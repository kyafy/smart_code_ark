<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { projectApi } from '@/api/endpoints'
import type { ProjectSummary } from '@smartark/domain/api'

const loading = ref(false)
const list = ref<ProjectSummary[]>([])

const load = async () => {
  loading.value = true
  try {
    list.value = await projectApi.list()
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <view class="page">
    <view class="title">项目列表</view>
    <button size="mini" @click="load">刷新</button>
    <view v-if="loading">加载中...</view>
    <view v-for="item in list" :key="item.id" class="card">
      <view class="name">{{ item.title }}</view>
      <view class="meta">{{ item.status }}</view>
    </view>
    <view v-if="!loading && list.length === 0" class="empty">暂无项目</view>
  </view>
</template>

<style scoped>
.page { padding: 24rpx; }
.title { font-size: 34rpx; font-weight: 600; margin-bottom: 16rpx; }
.card { background: #fff; border-radius: 14rpx; padding: 18rpx; margin-bottom: 12rpx; border: 1rpx solid #e2e8f0; }
.name { font-size: 30rpx; font-weight: 500; }
.meta { margin-top: 8rpx; color: #64748b; font-size: 24rpx; }
.empty { color: #94a3b8; margin-top: 20rpx; }
</style>
