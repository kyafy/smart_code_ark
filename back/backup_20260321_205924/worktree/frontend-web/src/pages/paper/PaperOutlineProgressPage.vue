<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import LogPanel from '@/components/LogPanel.vue'
import StatusTag from '@/components/StatusTag.vue'
import StepTimeline from '@/components/StepTimeline.vue'
import { showApiError } from '@/api/http'
import { taskApi } from '@/api/endpoints'
import { useTaskStore } from '@/stores/task'
import { usePaperStore } from '@/stores/paper'
import { QUERY_FROM, QUERY_SOURCE_TASK_ID } from '@/constants/navigation'

const route = useRoute()
const router = useRouter()
const task = useTaskStore()
const paper = usePaperStore()

const taskId = computed(() => String(route.params.taskId))
const isPolling = ref(false)
let pollTimer: number | null = null
const paperSteps = [
  { code: 'topic_clarify', name: '主题澄清' },
  { code: 'academic_retrieve', name: '学术检索' },
  { code: 'outline_generate', name: '大纲生成' },
  { code: 'outline_quality_check', name: '大纲质检' },
]

const onViewResult = () => {
  router.push({
    name: 'paper-manuscript',
    params: { taskId: taskId.value },
    query: { [QUERY_FROM]: 'paper', [QUERY_SOURCE_TASK_ID]: taskId.value }
  })
}

const poll = async () => {
  if (!taskId.value) return
  isPolling.value = true
  try {
    await task.loadStatus(taskId.value)
    paper.taskId = taskId.value
    if (task.isFinished || task.isFailed || task.isCancelled) {
      if (pollTimer) {
        window.clearInterval(pollTimer)
        pollTimer = null
      }
      if (task.isFinished) {
        onViewResult()
      }
    }
  } catch (e) {
    showApiError(e)
  } finally {
    isPolling.value = false
  }
}

onMounted(() => {
  task.reset()
  void poll()
  pollTimer = window.setInterval(() => {
    void poll()
  }, 2000)
})

onBeforeUnmount(() => {
  if (pollTimer) window.clearInterval(pollTimer)
})

const onCancel = async () => {
  await taskApi.cancel(taskId.value)
  await poll()
}

const onRetry = async () => {
  const stepCode = task.currentStep || 'topic_clarify'
  await taskApi.retry(taskId.value, stepCode)
  await poll()
  if (!pollTimer) {
    pollTimer = window.setInterval(() => {
      void poll()
    }, 2000)
  }
}
</script>

<template>
  <div class="flex flex-col gap-4">
    <div class="rounded-2xl border bg-white p-5">
      <div class="flex items-start justify-between gap-4">
        <div>
          <div class="text-base font-semibold">论文框架生成进度</div>
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
        <StepTimeline :current-step="task.currentStep" :status="task.status" :steps="paperSteps" />
      </div>

      <div class="mt-6 flex items-center gap-2">
        <el-button @click="router.push({ name: 'paper-topic' })">返回主题填写</el-button>
        <el-button v-if="task.status === 'queued' || task.status === 'running'" type="warning" @click="onCancel">取消任务</el-button>
        <el-button v-if="task.status === 'failed' || task.status === 'cancelled'" type="primary" @click="onRetry">重试当前步骤</el-button>
        <el-button v-if="task.isFinished" type="primary" @click="onViewResult">查看论文文稿</el-button>
      </div>
    </div>

    <LogPanel :logs="task.logs" :show-hint="true" />
  </div>
</template>
