<template>
  <div class="h-screen w-full flex flex-col bg-gray-100">
    <div class="h-14 bg-white border-b flex items-center px-4 justify-between shadow-sm z-10">
      <div class="flex items-center gap-2">
        <h1 class="font-semibold text-lg">Project Preview</h1>
<<<<<<< HEAD
        <span class="text-xs px-2 py-0.5 bg-green-100 text-green-700 rounded-full">Live</span>
=======
        <span v-if="previewStatus === 'ready'" class="text-xs px-2 py-0.5 bg-green-100 text-green-700 rounded-full">Live</span>
        <span v-else-if="previewStatus === 'provisioning'" class="text-xs px-2 py-0.5 bg-amber-100 text-amber-700 rounded-full">Deploying</span>
        <span v-else-if="previewStatus === 'expired'" class="text-xs px-2 py-0.5 bg-gray-100 text-gray-500 rounded-full">Expired</span>
        <span v-else class="text-xs px-2 py-0.5 bg-red-100 text-red-700 rounded-full">{{ previewStatus }}</span>
>>>>>>> origin/master
      </div>
      <div class="flex items-center gap-4">
        <div class="text-sm text-gray-500">
          Task ID: {{ route.params.taskId }}
        </div>
        <el-button type="primary" size="small" @click="handleDownload">
          下载代码
        </el-button>
        <el-button size="small" @click="router.push({ name: 'projects' })">
          返回项目
        </el-button>
      </div>
    </div>
<<<<<<< HEAD
    
    <div class="flex-1 overflow-hidden relative">
      <iframe 
        v-if="previewUrl" 
        :src="previewUrl" 
        class="w-full h-full border-none"
        title="Project Preview"
      ></iframe>
      <div v-else-if="loading" class="absolute inset-0 flex items-center justify-center">
        <el-icon class="is-loading text-4xl text-blue-500"><Loading /></el-icon>
=======

    <div class="flex-1 overflow-hidden relative">
      <iframe
        v-if="previewUrl && previewStatus === 'ready'"
        :src="previewUrl"
        class="w-full h-full border-none"
        sandbox="allow-scripts allow-same-origin"
        title="Project Preview"
      ></iframe>
      <div v-else-if="loading || previewStatus === 'provisioning'" class="absolute inset-0 flex items-center justify-center">
        <div class="bg-white p-8 rounded-lg shadow-lg max-w-md w-full mx-4 text-center">
          <el-icon class="is-loading text-4xl text-blue-500 mb-4"><Loading /></el-icon>
          <h2 class="text-xl font-bold text-gray-900 mb-2">正在部署预览</h2>
          <p class="text-gray-500 text-sm">{{ previewPhaseLabel || '请稍候...' }}</p>
        </div>
>>>>>>> origin/master
      </div>
      <div v-else class="absolute inset-0 flex items-center justify-center">
        <div class="bg-white p-8 rounded-lg shadow-lg max-w-2xl w-full mx-4 text-center">
          <div class="w-16 h-16 bg-amber-100 text-amber-600 rounded-full flex items-center justify-center mx-auto mb-4">
            <el-icon class="text-3xl"><Warning /></el-icon>
          </div>
<<<<<<< HEAD
          <h2 class="text-2xl font-bold text-gray-900 mb-2">预览不可用</h2>
=======
          <h2 class="text-2xl font-bold text-gray-900 mb-2">
            {{ previewStatus === 'expired' ? '预览已过期' : '预览不可用' }}
          </h2>
>>>>>>> origin/master
          <p class="text-gray-500 mb-6">
            {{ errorMessage || '当前任务暂无预览地址，或任务尚未完成。' }}<br/>
            您可以直接下载生成的应用代码在本地运行。
          </p>
          <el-button type="primary" size="large" @click="handleDownload">
            下载应用代码
          </el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
<<<<<<< HEAD
import { ref, onMounted } from 'vue'
=======
import { computed, ref, onMounted, onBeforeUnmount } from 'vue'
>>>>>>> origin/master
import { useRoute, useRouter } from 'vue-router'
import { taskApi } from '@/api/endpoints'
import { Loading, Warning } from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()
const taskId = String(route.params.taskId)

const previewUrl = ref('')
<<<<<<< HEAD
const loading = ref(true)
const errorMessage = ref('')

onMounted(async () => {
  try {
    const res = await taskApi.preview(taskId)
    if (res.previewUrl) {
      previewUrl.value = res.previewUrl
    } else {
      errorMessage.value = '未返回有效的预览地址'
=======
const previewStatus = ref('provisioning')
const previewPhase = ref<string | null>(null)
const loading = ref(true)
const errorMessage = ref('')
let pollTimer: number | null = null

const PHASE_LABELS: Record<string, string> = {
  prepare_artifact: '准备部署产物...',
  start_runtime: '启动运行环境...',
  install_deps: '安装依赖...',
  boot_service: '启动服务...',
  health_check: '健康检查...',
  publish_gateway: '发布访问入口...',
}

const previewPhaseLabel = computed(() =>
  previewPhase.value ? PHASE_LABELS[previewPhase.value] || previewPhase.value : null
)

const loadPreview = async () => {
  try {
    const res = await taskApi.preview(taskId)
    previewStatus.value = res.status || 'failed'
    previewUrl.value = res.previewUrl || ''
    previewPhase.value = res.phase || null
    if (res.lastError) errorMessage.value = res.lastError

    if (previewStatus.value === 'provisioning') {
      startPolling()
    } else {
      stopPolling()
>>>>>>> origin/master
    }
  } catch (error: any) {
    console.error('Failed to get preview URL', error)
    errorMessage.value = error.response?.data?.message || '获取预览地址失败'
<<<<<<< HEAD
  } finally {
    loading.value = false
  }
})
=======
    previewStatus.value = 'failed'
    stopPolling()
  } finally {
    loading.value = false
  }
}

const startPolling = () => {
  if (pollTimer) return
  pollTimer = window.setInterval(() => void loadPreview(), 3000)
}

const stopPolling = () => {
  if (!pollTimer) return
  window.clearInterval(pollTimer)
  pollTimer = null
}

onMounted(() => void loadPreview())
onBeforeUnmount(() => stopPolling())
>>>>>>> origin/master

const handleDownload = () => {
  window.open(`/api/task/${taskId}/download`, '_blank')
}
</script>
