<script setup lang="ts">
import { ref } from 'vue'
import { useChatStore } from '@/stores/chat'

const chat = useChatStore()
const input = ref('')
const sending = ref(false)

const send = async () => {
  if (!input.value.trim()) return
  sending.value = true
  const content = input.value
  input.value = ''
  try {
    await chat.sendMessage(content)
  } catch {
    uni.showToast({ title: '发送失败', icon: 'none' })
  } finally {
    sending.value = false
  }
}
</script>

<template>
  <view class="page">
    <view class="title">聊天</view>
    <view class="messages">
      <view v-for="(m, idx) in chat.messages" :key="idx" class="msg" :class="m.role">
        <view class="role">{{ m.role }}</view>
        <view class="content">{{ m.content || '...' }}</view>
      </view>
    </view>
    <view class="composer">
      <input v-model="input" class="input" placeholder="请输入内容" />
      <button size="mini" :loading="sending" @click="send">发送</button>
    </view>
  </view>
</template>

<style scoped>
.page { padding: 24rpx; display: flex; flex-direction: column; gap: 16rpx; }
.title { font-size: 34rpx; font-weight: 600; }
.messages { display: flex; flex-direction: column; gap: 12rpx; min-height: 60vh; }
.msg { border-radius: 14rpx; padding: 14rpx; background: #fff; border: 1rpx solid #e2e8f0; }
.msg.assistant { background: #eff6ff; }
.role { font-size: 22rpx; color: #64748b; margin-bottom: 6rpx; }
.content { font-size: 28rpx; }
.composer { display: flex; gap: 8rpx; align-items: center; }
.input { flex: 1; background: #fff; border-radius: 12rpx; border: 1rpx solid #e2e8f0; padding: 14rpx; }
</style>
