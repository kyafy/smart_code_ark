<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { showApiError } from '@/api/http'
import { useChatStore } from '@/stores/chat'
import { PAPER_CODEGEN_SEED_KEY, QUERY_FROM, QUERY_SOURCE_TASK_ID } from '@/constants/navigation'
import type { CodegenSeedSpec } from '@/mappers/paperToCodegen'

const router = useRouter()
const route = useRoute()
const chat = useChatStore()

const isSubmitting = ref(false)
const form = ref({ title: '', description: '', projectType: 'web' })
const seedHint = ref('')

onMounted(() => {
  const from = String(route.query[QUERY_FROM] ?? '')
  if (from !== 'paper') return
  const raw = sessionStorage.getItem(PAPER_CODEGEN_SEED_KEY)
  if (!raw) return
  try {
    const seed = JSON.parse(raw) as CodegenSeedSpec
    form.value.title = seed.title || form.value.title
    form.value.description = seed.description || form.value.description
    form.value.projectType = seed.projectType || form.value.projectType
    const sourceTaskId = String(route.query[QUERY_SOURCE_TASK_ID] ?? '')
    seedHint.value = sourceTaskId ? `已从论文任务 ${sourceTaskId} 注入生成草案` : '已从论文结果注入生成草案'
  } catch {
    return
  }
})

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
    <div v-if="seedHint" class="mt-3 rounded-xl bg-blue-50 px-3 py-2 text-xs text-blue-700">
      {{ seedHint }}
    </div>

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
