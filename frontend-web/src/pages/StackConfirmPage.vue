<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { projectApi, taskApi } from '@/api/endpoints'
import { showApiError } from '@/api/http'
import type { StackConfig } from '@/types/api'
import { useChatStore } from '@/stores/chat'

const router = useRouter()
const route = useRoute()
const chatStore = useChatStore()

const sessionId = computed(() => String(route.query.sessionId ?? ''))
const isSubmitting = ref(false)

const form = ref<StackConfig>({
  backend: 'fastapi',
  frontend: 'vue3',
  db: 'mysql',
})

const backendOptions = [
  { label: 'FastAPI', value: 'fastapi' },
  { label: 'Spring Boot', value: 'springboot' },
  { label: 'Django', value: 'django' },
  { label: 'NestJS', value: 'nestjs' },
]

const frontendOptions = [
  { label: 'Vue3', value: 'vue3' },
  { label: 'React', value: 'react' },
]

const dbOptions = [
  { label: 'MySQL', value: 'mysql' },
  { label: 'PostgreSQL', value: 'postgres' },
]

const onConfirmAndGenerate = async () => {
  if (!sessionId.value) {
    ElMessage.warning('缺少 sessionId，请返回对话页重试')
    return
  }
  isSubmitting.value = true
  try {
    const prdData = chatStore.extractedRequirements ? JSON.stringify(chatStore.extractedRequirements) : ''
    const confirmed = await projectApi.confirm({ 
      sessionId: sessionId.value, 
      stack: form.value,
      prd: prdData
    })
    const gen = await taskApi.generate({ projectId: confirmed.projectId })
    await router.replace({ name: 'task-progress', params: { taskId: gen.taskId } })
  } catch (e) {
    showApiError(e)
  } finally {
    isSubmitting.value = false
  }
}
</script>

<template>
  <div class="rounded-2xl border bg-white p-5">
    <div class="text-base font-semibold">选择技术栈</div>
    <div class="mt-1 text-sm text-slate-500">需求已通过对话梳理完成，现在选择技术栈并开始生成项目工程。</div>

    <div class="mt-6 grid grid-cols-1 gap-4 md:grid-cols-3">
      <div>
        <div class="mb-2 text-sm font-medium">后端</div>
        <el-select v-model="form.backend" placeholder="请选择">
          <el-option v-for="o in backendOptions" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
      </div>
      <div>
        <div class="mb-2 text-sm font-medium">前端</div>
        <el-select v-model="form.frontend" placeholder="请选择">
          <el-option v-for="o in frontendOptions" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
      </div>
      <div>
        <div class="mb-2 text-sm font-medium">数据库</div>
        <el-select v-model="form.db" placeholder="请选择">
          <el-option v-for="o in dbOptions" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
      </div>
    </div>

    <div class="mt-6 flex items-center gap-2">
      <el-button @click="router.back()">返回</el-button>
      <el-button type="primary" :loading="isSubmitting" @click="onConfirmAndGenerate">确认并开始生成</el-button>
    </div>
  </div>
</template>

