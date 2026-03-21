<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import LogPanel from '@/components/LogPanel.vue'
import StatusTag from '@/components/StatusTag.vue'
import StepTimeline from '@/components/StepTimeline.vue'
import { showApiError } from '@/api/http'
import { taskApi } from '@/api/endpoints'
import { useTaskStore } from '@/stores/task'

const route = useRoute()
const router = useRouter()
const task = useTaskStore()

const taskId = computed(() => String(route.params.taskId))
const isPolling = ref(false)
const previewLoading = ref(false)
const previewStatus = ref('provisioning')
const previewUrl = ref('')
const previewExpireAt = ref('')
const previewLastError = ref('')
const isRebuildingPreview = ref(false)
const isReleasingPreview = ref(false)
const isModifying = ref(false)
const changeInstructions = ref('')
const relationLoading = ref(false)
const relationItems = ref<Array<{
  childTaskId: string
  relationType: string
  chatSessionId?: string | null
  taskStatus: string
  taskType: string
  createdAt?: string | null
}>>([])
let pollTimer: number | null = null

const resetPreviewState = () => {
  previewLoading.value = false
  previewStatus.value = 'provisioning'
  previewUrl.value = ''
  previewExpireAt.value = ''
  previewLastError.value = ''
  isRebuildingPreview.value = false
}

const stopPolling = () => {
  if (pollTimer) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

const startPolling = () => {
  stopPolling()
  pollTimer = window.setInterval(() => {
    void poll()
  }, 2000)
}

const initTaskPage = async () => {
  task.reset()
  resetPreviewState()
  isPolling.value = false
  isModifying.value = false
  await poll()
  startPolling()
}

const poll = async () => {
  if (!taskId.value) return
  isPolling.value = true
  try {
    await task.loadStatus(taskId.value)
    if (task.isFinished) {
      await loadPreview(true)
      await loadRelations(true)
    }
    if (task.isFinished || task.isFailed || task.isCancelled) {
      if (pollTimer) {
        window.clearInterval(pollTimer)
        pollTimer = null
      }
    }
  } catch (e) {
    showApiError(e)
  } finally {
    isPolling.value = false
  }
}

onMounted(() => {
  void initTaskPage()
})

watch(taskId, (next, prev) => {
  if (!next || next === prev) return
  void initTaskPage()
})

onBeforeUnmount(() => {
  stopPolling()
})

// Use backend returned projectId to navigate to project detail
const projectId = computed(() => task.rawStatus?.projectId)

const onViewResult = () => {
  if (projectId.value) {
    router.push({ name: 'project-detail', params: { projectId: projectId.value } })
  }
}

const onCancel = async () => {
  await taskApi.cancel(taskId.value)
  await poll()
}

const onRetry = async () => {
  const stepCode = task.currentStep || 'requirement_analyze'
  await taskApi.retry(taskId.value, stepCode)
  await poll()
  if (!pollTimer) startPolling()
}

const loadPreview = async (silent = false) => {
  if (!taskId.value || !task.isFinished) return
  if (previewLoading.value) return
  previewLoading.value = true
  try {
    const p = await taskApi.preview(taskId.value)
    previewStatus.value = p.status || 'failed'
    previewUrl.value = p.previewUrl || ''
    previewExpireAt.value = p.expireAt || ''
    previewLastError.value = p.lastError || ''
  } catch (e) {
    previewStatus.value = 'failed'
    previewLastError.value = '获取预览状态失败'
    if (!silent) showApiError(e)
  } finally {
    previewLoading.value = false
  }
}

const loadRelations = async (silent = false) => {
  if (!taskId.value) return
  if (relationLoading.value) return
  relationLoading.value = true
  try {
    const result = await taskApi.relations(taskId.value)
    relationItems.value = Array.isArray(result.relations) ? result.relations : []
  } catch (e) {
    relationItems.value = []
    if (!silent) showApiError(e)
  } finally {
    relationLoading.value = false
  }
}

const onRebuildPreview = async () => {
  if (!task.isFinished || isRebuildingPreview.value) return
  isRebuildingPreview.value = true
  try {
    const p = await taskApi.rebuildPreview(taskId.value)
    previewStatus.value = p.status || 'provisioning'
    previewUrl.value = p.previewUrl || ''
    previewExpireAt.value = p.expireAt || ''
    previewLastError.value = p.lastError || ''
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
    const p = await taskApi.releasePreview(taskId.value)
    previewStatus.value = p.status || 'expired'
    previewUrl.value = p.previewUrl || ''
    previewExpireAt.value = p.expireAt || ''
    previewLastError.value = p.lastError || ''
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
  if (isModifying.value) return
  isModifying.value = true
  const projectId = task.rawStatus?.projectId ? String(task.rawStatus.projectId) : ''
  console.info('[modify-plan] route to chat', { sourceTaskId: taskId.value, projectId, textLength: text.length })
  try {
    await router.push({
      name: 'chat',
      params: { sessionId: 'new' },
      query: {
        mode: 'modify_plan',
        sourceTaskId: taskId.value,
        projectId,
        initialMessage: text
      }
    })
    ElMessage.success('已进入迭代对话，请确认编排')
  } catch (e) {
    console.error('[modify-plan] route failed', { sourceTaskId: taskId.value, error: e })
    showApiError(e)
  } finally {
    isModifying.value = false
  }
}

const onOpenPreview = () => {
  if (!previewUrl.value) return
  window.open(previewUrl.value, '_blank', 'noopener,noreferrer')
}
</script>

<template>
  <div class="flex flex-col gap-4">
    <div class="rounded-2xl border bg-white p-5">
      <div class="flex items-start justify-between gap-4">
        <div>
          <div class="text-base font-semibold">生成进度</div>
          <div class="mt-1 text-sm text-slate-500">任务 ID：{{ taskId }}</div>
        </div>
        <div class="flex items-center gap-2">
          <StatusTag :status="task.status" />
          <el-button size="small" :loading="isPolling" @click="poll">刷新</el-button>
        </div>
      </div>

      <div class="mt-4">
        <el-progress :percentage="task.progress" :stroke-width="10" :status="task.status === 'failed' ? 'exception' : (task.status === 'finished' ? 'success' : '')" />
        <div class="mt-2 text-xs text-slate-500">当前步骤：{{ task.currentStep || '—' }}</div>
        <div v-if="task.status === 'failed' && task.rawStatus?.errorMessage" class="mt-2 text-xs text-red-500">
          错误信息：{{ task.rawStatus.errorMessage }}
        </div>
        <div v-if="task.status === 'cancelled'" class="mt-2 text-xs text-amber-600">
          任务已取消
        </div>
      </div>

      <div class="mt-6">
        <StepTimeline :current-step="task.currentStep" :status="task.status" />
      </div>

      <div class="mt-6 flex items-center gap-2">
        <el-button @click="router.push({ name: 'projects' })">返回项目</el-button>
        <el-button v-if="task.status === 'queued' || task.status === 'running'" type="warning" @click="onCancel">取消任务</el-button>
        <el-button v-if="task.status === 'failed' || task.status === 'cancelled'" type="primary" @click="onRetry">重试当前步骤</el-button>
        <el-button v-if="task.isFinished" type="primary" @click="onViewResult">查看项目详情</el-button>
      </div>
    </div>

    <div v-if="task.isFinished" class="grid grid-cols-1 gap-4 lg:grid-cols-3">
      <div class="lg:col-span-2 rounded-2xl border bg-white p-5">
        <div class="flex items-center justify-between gap-2">
          <div class="text-sm font-semibold">在线预览</div>
          <div class="flex items-center gap-2">
            <el-button size="small" :loading="previewLoading" @click="loadPreview">刷新预览状态</el-button>
            <el-button
              size="small"
              type="warning"
              plain
              :loading="isReleasingPreview"
              @click="onReleasePreview"
            >
              关闭预览
            </el-button>
            <el-tag v-if="previewStatus === 'ready'" type="success" effect="plain">ready</el-tag>
            <el-tag v-else-if="previewStatus === 'provisioning'" type="warning" effect="plain">provisioning</el-tag>
            <el-tag v-else-if="previewStatus === 'expired'" type="info" effect="plain">expired</el-tag>
            <el-tag v-else type="danger" effect="plain">{{ previewStatus || 'failed' }}</el-tag>
          </div>
        </div>

        <div v-if="previewStatus === 'ready' && previewUrl" class="mt-3">
          <div class="h-[520px] overflow-hidden rounded-xl border">
            <iframe :src="previewUrl" class="h-full w-full border-0" title="Task Preview" />
          </div>
          <div class="mt-3 flex items-center justify-between gap-2">
            <div class="text-xs text-slate-500">过期时间：{{ previewExpireAt || '—' }}</div>
            <el-button size="small" type="primary" @click="onOpenPreview">新窗口打开</el-button>
          </div>
        </div>

        <div v-else-if="previewStatus === 'provisioning'" class="mt-3 rounded-xl bg-amber-50 p-3 text-sm text-amber-700">
          预览部署中，请稍后刷新查看结果。
        </div>

        <div v-else-if="previewStatus === 'failed' || previewStatus === 'expired'" class="mt-3 rounded-xl bg-rose-50 p-3 text-sm text-rose-700">
          <div>预览不可用：{{ previewLastError || '请重建预览或下载 ZIP 在本地运行。' }}</div>
          <el-button class="mt-3" size="small" type="warning" plain :loading="isRebuildingPreview" @click="onRebuildPreview">
            重建预览
          </el-button>
        </div>

        <div v-else class="mt-3 rounded-xl bg-slate-100 p-3 text-sm text-slate-600">
          当前暂无可展示的预览信息，请稍后刷新。
        </div>
      </div>

      <div class="lg:col-span-1">
        <div class="rounded-2xl border bg-white p-5">
          <div class="flex items-center justify-between gap-2">
            <div class="text-sm font-semibold">迭代链路</div>
            <el-button size="small" :loading="relationLoading" @click="loadRelations">刷新</el-button>
          </div>
          <div v-if="relationItems.length === 0" class="mt-3 rounded-xl bg-slate-50 p-3 text-xs text-slate-500">
            当前任务暂无迭代子任务
          </div>
          <div v-else class="mt-3 space-y-2">
            <button
              v-for="item in relationItems"
              :key="item.childTaskId"
              type="button"
              class="w-full rounded-xl border bg-slate-50 p-3 text-left transition hover:bg-slate-100"
              @click="router.push({ name: 'task-progress', params: { taskId: item.childTaskId } })"
            >
              <div class="text-xs font-semibold text-slate-700">{{ item.childTaskId }}</div>
              <div class="mt-1 flex flex-wrap items-center gap-2 text-[11px] text-slate-500">
                <span>类型：{{ item.taskType }}</span>
                <span>状态：{{ item.taskStatus }}</span>
                <span v-if="item.chatSessionId">会话：{{ item.chatSessionId }}</span>
              </div>
            </button>
          </div>
        </div>
        <div class="mt-4 rounded-2xl border bg-white p-5">
          <div class="text-sm font-semibold">继续修改</div>
          <div class="mt-1 text-xs text-slate-500">用中文描述改动，例如：订单模块增加待发货状态</div>
          <el-input
            v-model="changeInstructions"
            class="mt-4"
            type="textarea"
            :autosize="{ minRows: 4, maxRows: 8 }"
            placeholder="请输入修改指令"
          />
          <el-button class="mt-3 w-full" type="primary" :loading="isModifying" @click="onModify">
            进入迭代对话
          </el-button>
          <div class="mt-4 rounded-xl bg-slate-50 p-3 text-xs text-slate-600">
            建议一次只提出一个明确修改点，便于模型准确应用。
          </div>
        </div>
      </div>
    </div>

    <LogPanel :logs="task.logs" :show-hint="true" />
  </div>
</template>
