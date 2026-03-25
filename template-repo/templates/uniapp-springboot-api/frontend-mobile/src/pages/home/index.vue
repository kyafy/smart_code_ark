<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getHealth, getNews, type NewsItem } from '../../utils/api'

const status = ref('connecting')
const service = ref('')
const news = ref<NewsItem[]>([])
const error = ref('')

onMounted(async () => {
  try {
    const [health, newsList] = await Promise.all([getHealth(), getNews()])
    status.value = health.status
    service.value = health.service
    news.value = newsList
  } catch (err) {
    error.value = err instanceof Error ? err.message : '接口请求失败'
  }
})
</script>

<template>
  <view class="page">
    <view class="hero">
      <text class="eyebrow">Template Repo v1</text>
      <text class="title">__DISPLAY_NAME__</text>
      <text class="subtitle">UniApp + Spring Boot API 模板首页</text>
    </view>

    <view class="card">
      <text class="card-title">接口状态</text>
      <text class="status">{{ status }}</text>
      <text class="muted">{{ service }}</text>
      <text v-if="error" class="error">{{ error }}</text>
    </view>

    <view class="card">
      <text class="card-title">首页卡片示例</text>
      <view v-for="item in news" :key="item.id" class="news-item">
        <text class="news-title">{{ item.title }}</text>
        <text class="news-summary">{{ item.summary }}</text>
        <text class="news-action">{{ item.actionText }}</text>
      </view>
    </view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 32rpx;
  background: linear-gradient(180deg, #f7fbff 0%, #eef5ff 100%);
}

.hero {
  display: flex;
  flex-direction: column;
  gap: 14rpx;
  margin-bottom: 28rpx;
}

.eyebrow {
  color: #2070d8;
  font-size: 24rpx;
  letter-spacing: 4rpx;
  text-transform: uppercase;
}

.title {
  font-size: 64rpx;
  font-weight: 700;
  color: #172538;
}

.subtitle {
  color: #5e6f85;
  font-size: 28rpx;
}

.card {
  background: rgba(255, 255, 255, 0.92);
  border-radius: 28rpx;
  padding: 28rpx;
  margin-bottom: 20rpx;
  box-shadow: 0 18rpx 40rpx rgba(23, 37, 56, 0.08);
}

.card-title {
  display: block;
  margin-bottom: 16rpx;
  font-size: 30rpx;
  font-weight: 700;
  color: #172538;
}

.status {
  display: block;
  margin-bottom: 10rpx;
  font-size: 44rpx;
  color: #0b8f61;
  font-weight: 700;
}

.muted {
  display: block;
  color: #607186;
}

.error {
  display: block;
  margin-top: 10rpx;
  color: #c7254e;
}

.news-item {
  padding: 24rpx 0;
  border-top: 1px solid #eef2f7;
}

.news-item:first-of-type {
  border-top: 0;
  padding-top: 0;
}

.news-title {
  display: block;
  font-size: 30rpx;
  font-weight: 700;
  color: #172538;
}

.news-summary {
  display: block;
  margin-top: 8rpx;
  color: #607186;
  font-size: 26rpx;
}

.news-action {
  display: inline-block;
  margin-top: 12rpx;
  color: #2070d8;
  font-size: 24rpx;
  font-weight: 700;
}
</style>
