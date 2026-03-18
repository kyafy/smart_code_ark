<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { showApiError } from '@/api/http'
import { useChatStore } from '@/stores/chat'

const router = useRouter()
const chat = useChatStore()

const isSubmitting = ref(false)
const form = ref({ title: '', description: '', projectType: 'web' })

const onSubmit = async () => {
  const title = form.value.title.trim()
  if (!title) {
    ElMessage.warning('请输入项目标题')
    return
  }
  isSubmitting.value = true
  try {
    const sessionId = await chat.startSession({
      title,
      description: form.value.description.trim() || undefined,
      projectType: form.value.projectType,
    })
    const initMsg = `我想要做一个关于“${form.value.title}”的项目，要求：${form.value.description}`
    // Do not wait for send, redirect immediately with query param
    await router.push({ name: 'chat', params: { sessionId }, query: { initialMessage: initMsg } })
  } catch (e) {
    showApiError(e)
  } finally {
    isSubmitting.value = false
  }
}
</script>

<template>
  <div class="rounded-2xl border bg-white p-5">
    <div class="text-base font-semibold">新建会话</div>
    <div class="mt-1 text-sm text-slate-500">先通过对话梳理需求，再选择技术栈生成项目工程</div>

    <div class="mt-6 grid grid-cols-1 gap-4">
      <div>
        <div class="mb-2 text-sm font-medium">项目标题</div>
        <el-input v-model="form.title" placeholder="例如：校园二手交易平台" />
      </div>

      <div>
        <div class="mb-2 text-sm font-medium">项目描述（可选）</div>
        <el-input v-model="form.description" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }" placeholder="一句话描述项目范围" />
      </div>

      <div>
        <div class="mb-2 text-sm font-medium">项目类型</div>
        <el-select v-model="form.projectType" placeholder="请选择">
          <el-option label="Web" value="web" />
          <el-option label="H5" value="h5" />
          <el-option label="小程序" value="miniprogram" />
          <el-option label="APP" value="app" />
        </el-select>
      </div>

      <div class="flex items-center gap-2">
        <el-button @click="router.push({ name: 'projects' })">返回</el-button>
        <el-button type="primary" :loading="isSubmitting" @click="onSubmit">开始对话</el-button>
      </div>
    </div>
  </div>
</template>

