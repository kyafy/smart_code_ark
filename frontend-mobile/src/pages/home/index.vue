<script setup lang="ts">
const go = (url: string) => uni.navigateTo({ url })
</script>

<template>
  <view class="page">
    <view class="title">首页</view>
    <button @click="go('/pages/projects/index')">项目列表</button>
    <button @click="go('/pages/chat/index')">聊天</button>
    <button @click="go('/pages/tasks/index')">任务进度</button>
    <button @click="go('/pages/profile/index')">个人中心</button>
    <button @click="go('/pages/recharge/index')">积分充值</button>
  </view>
</template>

<style scoped>
.page { padding: 32rpx; display: flex; flex-direction: column; gap: 16rpx; }
.title { font-size: 34rpx; font-weight: 600; margin-bottom: 10rpx; }
</style>
