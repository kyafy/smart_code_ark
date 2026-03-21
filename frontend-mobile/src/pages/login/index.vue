<script setup lang="ts">
import { ref } from 'vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const username = ref('demo')
const password = ref('123456')
const loading = ref(false)

const submit = async () => {
  loading.value = true
  try {
    await auth.login(username.value, password.value)
    uni.showToast({ title: '登录成功', icon: 'success' })
    uni.reLaunch({ url: '/pages/home/index' })
  } catch (e) {
    uni.showToast({ title: '登录失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <view class="page">
    <view class="title">Smart Code Ark 移动端</view>
    <input v-model="username" class="input" placeholder="用户名" />
    <input v-model="password" class="input" placeholder="密码" password />
    <button :loading="loading" @click="submit">登录</button>
  </view>
</template>

<style scoped>
.page { padding: 32rpx; display: flex; flex-direction: column; gap: 20rpx; }
.title { font-size: 36rpx; font-weight: 600; margin-bottom: 12rpx; }
.input { background: #fff; border-radius: 12rpx; padding: 18rpx; border: 1rpx solid #e2e8f0; }
</style>
