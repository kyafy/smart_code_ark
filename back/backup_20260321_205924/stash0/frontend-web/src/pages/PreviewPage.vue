<template>
  <div class="h-screen w-full flex flex-col bg-gray-100">
    <div class="h-14 bg-white border-b flex items-center px-4 justify-between shadow-sm z-10">
      <div class="flex items-center gap-2">
        <h1 class="font-semibold text-lg">Project Preview</h1>
        <span class="text-xs px-2 py-0.5 bg-green-100 text-green-700 rounded-full">Live</span>
      </div>
      <div class="flex items-center gap-4">
        <div class="text-sm text-gray-500">
          Task ID: {{ route.params.taskId }}
        </div>
        <el-button
          v-if="canRebuildPreview"
          :loading="isRebuildingPreview"
          size="small"
          type="warning"
          plain
          @click="onRebuildPreview"
        >
          重建预览
        </el-button>
        <el-button
          v-if="previewStatus === 'ready' || previewStatus === 'provisioning'"
          :loading="isReleasingPreview"
          size="small"
          type="warning"
          plain
          @click="onReleasePreview"
        >
          关闭预览
        </el-button>
        <el-button type="primary" size="small" @click="handleDownload">
          下载代码
        </el-button>
        <el-button size="small" type="primary" plain @click="router.push({ name: 'task-progress', params: { taskId } })">
          进入编排页
        </el-button>
        <el-button size="small" @click="router.push({ name: 'projects' })">
          返回项目
        </el-button>
      </div>
    </div>
    
    <div class="flex-1 overflow-hidden relative">
      <iframe 
        v-if="previewUrl" 
        :src="previewUrl" 
        class="w-full h-full border-none"
        title="Project Preview"
      ></iframe>
      <div v-else-if="loading" class="absolute inset-0 flex items-center justify-center">
        <el-icon class="is-loading text-4xl text-blue-500"><Loading /></el-icon>
      </div>
      <div v-else class="absolute inset-0 flex items-center justify-center">
        <div class="bg-white p-8 rounded-lg shadow-lg max-w-2xl w-full mx-4 text-center">
          <div class="w-16 h-16 bg-amber-100 text-amber-600 rounded-full flex items-center justify-center mx-auto mb-4">
            <el-icon class="text-3xl"><Warning /></el-icon>
          </div>
          <h2 class="text-2xl font-bold text-gray-900 mb-2">预览不可用</h2>
          <p class="text-gray-500 mb-6">
            {{ errorMessage || '当前任务暂无预览地址，或任务尚未完成。' }}<br/>
            您可以直接下载生成的应用代码在本地运行。
          </p>
          <div class="flex items-center justify-center gap-3">
            <el-button
              v-if="canRebuildPreview"
              :loading="isRebuildingPreview"
              type="warning"
              plain
              @click="onRebuildPreview"
            >
              重建预览
            </el-button>
            <el-button type="primary" @click="handleDownload">
              下载应用代码
            </el-button>
          </div>
          <div class="mt-5 rounded-xl bg-slate-50 p-4 text-left">
            <div class="mb-3 rounded-lg bg-blue-50 px-3 py-2 text-xs text-blue-700">
              建议在“编排页”统一处理预览与修改流程。
            </div>
            <div class="text-sm font-semibold text-slate-700">继续修改</div>
            <div class="mt-1 text-xs text-slate-500">用中文描述改动，例如：订单模块增加待发货状态</div>
            <el-input
              v-model="changeInstructions"
              class="mt-3"
              type="textarea"
              :autosize="{ minRows: 3, maxRows: 6 }"
              placeholder="请输入修改指令"
            />
            <el-button class="mt-3 w-full" type="primary" :loading="isModifying" @click="onModify">
              生成修改版
            </el-button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { taskApi } from '@/api/endpoints'
import { ElMessage } from 'element-plus'
import { showApiError } from '@/api/http'
import { Loading, Warning } from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()
const taskId = String(route.params.taskId)

const previewUrl = ref('')
const loading = ref(true)
const errorMessage = ref('')
const previewStatus = ref('provisioning')
const isRebuildingPreview = ref(false)
const isReleasingPreview = ref(false)
const changeInstructions = ref('')
const isModifying = ref(false)
const canRebuildPreview = ref(false)

const loadPreview = async () => {
  try {
    const res = await taskApi.preview(taskId)
    previewStatus.value = res.status || 'failed'
    canRebuildPreview.value = previewStatus.value === 'failed' || previewStatus.value === 'expired' || previewStatus.value === 'provisioning'
    if (res.previewUrl) {
      previewUrl.value = res.previewUrl
      errorMessage.value = ''
    } else {
      errorMessage.value = res.lastError || '未返回有效的预览地址'
    }
  } catch (error: any) {
    console.error('Failed to get preview URL', error)
    errorMessage.value = error.response?.data?.message || '获取预览地址失败'
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await loadPreview()
})

const handleDownload = async () => {
  try {
    const blob = await taskApi.download(taskId)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `smartark_${taskId}.zip`
    a.click()
    URL.revokeObjectURL(url)
  } catch (e) {
    showApiError(e)
  }
}

const onRebuildPreview = async () => {
  if (isRebuildingPreview.value) return
  isRebuildingPreview.value = true
  try {
    const p = await taskApi.rebuildPreview(taskId)
    previewStatus.value = p.status || 'provisioning'
    previewUrl.value = p.previewUrl || ''
    errorMessage.value = p.lastError || '预览重建已触发，请稍后刷新'
    canRebuildPreview.value = previewStatus.value === 'failed' || previewStatus.value === 'expired' || previewStatus.value === 'provisioning'
    ElMessage.success('已开始重建预览')
  } catch (e) {
    showApiError(e)
  } finally {
    isRebuildingPreview.value = false
  }
}

const onReleasePreview = async () => {
  if (isReleasingPreview.value) return
  isReleasingPreview.value = true
  try {
    const p = await taskApi.releasePreview(taskId)
    previewStatus.value = p.status || 'expired'
    previewUrl.value = p.previewUrl || ''
    errorMessage.value = p.lastError || '预览资源已释放'
    canRebuildPreview.value = true
    ElMessage.success('预览资源已释放')
  } catch (e) {
    showApiError(e)
  } finally {
    isReleasingPreview.value = false
  }
}

const onModify = async () => {
  const text = changeInstructions.value.trim()
  if (!text) {
    ElMessage.warning('请输入修改指令')
    return
  }
  isModifying.value = true
  try {
    const res = await taskApi.modify(taskId, { changeInstructions: text })
    await router.push({ name: 'task-progress', params: { taskId: res.taskId } })
  } catch (e) {
    showApiError(e)
  } finally {
    isModifying.value = false
  }
}
</script>
