<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { projectApi } from '@/api/endpoints'
import { showApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import type { ProjectDetail } from '@/types/api'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const projectId = computed(() => String(route.params.projectId))

const loading = ref(true)
const project = ref<ProjectDetail | null>(null)
const activeTab = ref('tasks')

const loadDetail = async () => {
  if (!projectId.value) return
  loading.value = true
  try {
    project.value = await projectApi.detail(projectId.value)
  } catch (e) {
    showApiError(e)
  } finally {
    loading.value = false
  }
}

const onDownload = async (taskId: string) => {
  try {
    const res = await fetch(`/api/task/${taskId}/download`, {
      headers: {
        'Authorization': `Bearer ${authStore.token}`
      }
    })
    
    if (!res.ok) {
      const json = await res.json()
      throw new Error(json.message || '下载失败')
    }
    
    const blob = await res.blob()
    const url = window.URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    // 尝试从 Content-Disposition 获取文件名，或使用默认值
    const disposition = res.headers.get('content-disposition')
    let filename = `project-${taskId}.zip`
    if (disposition && disposition.indexOf('attachment') !== -1) {
      const filenameRegex = /filename[^;=\n]*=((['"]).*?\2|[^;\n]*)/
      const matches = filenameRegex.exec(disposition)
      if (matches != null && matches[1]) { 
        filename = matches[1].replace(/['"]/g, '')
      }
    }
    a.download = filename
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    window.URL.revokeObjectURL(url)
  } catch (e) {
    showApiError(e)
  }
}

const onPreview = (taskId: string) => {
  const url = router.resolve({ name: 'preview', params: { taskId } }).href
  window.open(url, '_blank')
}

const onProgress = (taskId: string) => {
  router.push({ name: 'task-progress', params: { taskId } })
}

const parsedSpec = computed(() => {
  if (!project.value?.requirementSpec) return null
  try {
    return JSON.parse(project.value.requirementSpec)
  } catch {
    return null
  }
})

const getStatusType = (status: string) => {
  switch (status) {
    case 'finished': return 'success'
    case 'running': return 'primary'
    case 'failed': return 'danger'
    default: return 'info'
  }
}

const getStatusText = (status: string) => {
  switch (status) {
    case 'finished': return '已完成'
    case 'running': return '生成中'
    case 'failed': return '失败'
    case 'confirmed': return '待生成'
    default: return status
  }
}

onMounted(() => {
  loadDetail()
})
</script>

<template>
  <div class="h-full flex flex-col p-6 max-w-7xl mx-auto space-y-6 overflow-hidden">
    <div v-if="loading" class="flex justify-center items-center h-64">
      <el-spinner class="animate-spin text-primary w-8 h-8" />
    </div>

    <template v-else-if="project">
      <!-- 概览区 -->
      <div class="bg-surface rounded-xl p-6 shadow-sm border border-border">
        <div class="flex justify-between items-start">
          <div>
            <h1 class="text-2xl font-bold text-text-primary">{{ project.title }}</h1>
            <p class="text-text-secondary mt-2">{{ project.description }}</p>
          </div>
          <el-tag :type="getStatusType(project.status)" effect="light" round>
            {{ getStatusText(project.status) }}
          </el-tag>
        </div>
        
        <div class="mt-6 flex gap-4">
          <div class="flex flex-col">
            <span class="text-xs text-text-tertiary uppercase tracking-wider">后端</span>
            <span class="font-medium text-text-primary">{{ project.stack.backend }}</span>
          </div>
          <div class="w-px bg-border"></div>
          <div class="flex flex-col">
            <span class="text-xs text-text-tertiary uppercase tracking-wider">前端</span>
            <span class="font-medium text-text-primary">{{ project.stack.frontend }}</span>
          </div>
          <div class="w-px bg-border"></div>
          <div class="flex flex-col">
            <span class="text-xs text-text-tertiary uppercase tracking-wider">数据库</span>
            <span class="font-medium text-text-primary">{{ project.stack.db }}</span>
          </div>
        </div>
      </div>

      <!-- 详情区 -->
      <div class="flex-1 min-h-0 bg-surface rounded-xl shadow-sm border border-border flex flex-col">
        <el-tabs v-model="activeTab" class="px-6 pt-4 h-full flex flex-col">
          
          <el-tab-pane label="任务记录" name="tasks" class="h-full overflow-y-auto pb-6">
            <div v-if="project.tasks && project.tasks.length > 0" class="space-y-4">
              <div v-for="task in project.tasks" :key="task.id" 
                   class="p-4 border border-border rounded-lg flex justify-between items-center hover:border-primary/50 transition-colors">
                <div>
                  <div class="flex items-center gap-3">
                    <span class="font-medium text-text-primary">生成任务</span>
                    <el-tag :type="getStatusType(task.status)" size="small">{{ getStatusText(task.status) }}</el-tag>
                    <span class="text-xs text-text-tertiary">{{ new Date(task.createdAt).toLocaleString() }}</span>
                  </div>
                  <div class="mt-1 text-xs text-text-tertiary">任务ID：{{ task.id }}</div>
                  <div v-if="task.status === 'running'" class="mt-2 w-64">
                    <el-progress :percentage="task.progress" />
                  </div>
                  <div v-if="task.status === 'failed'" class="mt-2 text-sm text-danger">
                    {{ task.errorMessage }}
                  </div>
                </div>
                
                <div class="flex gap-2">
                  <el-button v-if="task.status !== 'finished'" type="primary" plain size="small" @click="onProgress(task.id)">
                    查看编排
                  </el-button>
                  <template v-if="task.status === 'finished'">
                    <el-button type="primary" plain size="small" @click="onPreview(task.id)">
                      预览
                    </el-button>
                    <el-button type="primary" size="small" @click="onDownload(task.id)">
                      下载源码
                    </el-button>
                  </template>
                </div>
              </div>
            </div>
            <el-empty v-else description="暂无生成记录" />
          </el-tab-pane>

          <el-tab-pane label="需求文档" name="prd" class="h-full overflow-y-auto pb-6">
            <div v-if="parsedSpec" class="max-w-4xl mx-auto space-y-6 pt-4">
              <div class="rounded-2xl border border-border bg-slate-50/50 p-6 dark:bg-slate-900/50">
                <div class="text-lg font-semibold text-text-primary mb-4">项目概览</div>
                <MarkdownRenderer :content="parsedSpec.description || '暂无描述'" />
              </div>
              
              <div v-if="parsedSpec.features && parsedSpec.features.length" class="rounded-2xl border border-border bg-slate-50/50 p-6 dark:bg-slate-900/50">
                <div class="text-lg font-semibold text-text-primary mb-4">核心功能</div>
                <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
                  <div v-for="(f, i) in parsedSpec.features" :key="i"
                       class="rounded-xl border border-border bg-surface px-4 py-3 text-sm text-text-secondary shadow-sm">
                    {{ f }}
                  </div>
                </div>
              </div>
              
              <div v-if="parsedSpec.pages && parsedSpec.pages.length" class="rounded-2xl border border-border bg-slate-50/50 p-6 dark:bg-slate-900/50">
                <div class="text-lg font-semibold text-text-primary mb-4">页面结构</div>
                <div class="flex flex-wrap gap-2">
                  <span v-for="(p, i) in parsedSpec.pages" :key="i" 
                        class="rounded-full bg-surface border border-border px-4 py-1.5 text-sm text-text-secondary shadow-sm">
                    {{ p }}
                  </span>
                </div>
              </div>

              <div v-if="parsedSpec.prd" class="rounded-2xl border border-border bg-slate-50/50 p-6 dark:bg-slate-900/50">
                <div class="text-lg font-semibold text-text-primary mb-4">完整需求文档 (PRD)</div>
                <MarkdownRenderer :content="parsedSpec.prd" />
              </div>
            </div>
            <el-empty v-else description="需求文档生成中或不存在" />
          </el-tab-pane>

          <el-tab-pane label="对话历史" name="chat" class="h-full flex flex-col pb-6">
            <div class="flex-1 overflow-y-auto pr-4 custom-scrollbar">
              <div class="space-y-4">
                <div v-for="(msg, idx) in project.messages || []" :key="idx"
                     class="flex" :class="msg.role === 'user' ? 'justify-end' : 'justify-start'">
                  <div class="max-w-[80%] rounded-2xl px-4 py-3 text-sm shadow-sm"
                       :class="msg.role === 'user' ? 'bg-blue-600 text-white' : 'bg-slate-100 text-slate-800 dark:bg-slate-800 dark:text-slate-200'">
                    <div class="whitespace-pre-wrap break-words">{{ msg.content }}</div>
                  </div>
                </div>
              </div>
            </div>
          </el-tab-pane>

        </el-tabs>
      </div>
    </template>
  </div>
</template>

<style scoped>
:deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
}
.custom-scrollbar::-webkit-scrollbar {
  width: 6px;
}
.custom-scrollbar::-webkit-scrollbar-thumb {
  background-color: var(--el-border-color-darker);
  border-radius: 4px;
}
</style>
