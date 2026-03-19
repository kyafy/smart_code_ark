<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import LogPanel from '@/components/LogPanel.vue'
import StatusTag from '@/components/StatusTag.vue'
import StepTimeline from '@/components/StepTimeline.vue'
import { showApiError } from '@/api/http'
import { useTaskStore } from '@/stores/task'

const route = useRoute()
const router = useRouter()
const task = useTaskStore()

const taskId = computed(() => String(route.params.taskId))
const isPolling = ref(false)
let pollTimer: number | null = null

const poll = async () => {
  if (!taskId.value) return
  isPolling.value = true
  try {
    await task.loadStatus(taskId.value)
    if (task.isFinished || task.isFailed) {
      if (pollTimer) {
        window.clearInterval(pollTimer)
        pollTimer = null
      }
      if (task.isFinished && projectId.value) {
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

// Extract projectId if backend returns it in task status, else fallback to projects
const projectId = computed(() => task.rawStatus?.projectId || '')

const onViewResult = () => {
  if (projectId.value) {
    router.push({ name: 'project-detail', params: { projectId: projectId.value } })
  } else {
    router.push({ name: 'projects' })
  }
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
      </div>

      <div class="mt-6">
        <StepTimeline :current-step="task.currentStep" :status="task.status" />
      </div>

      <div class="mt-6 flex items-center gap-2">
        <el-button @click="router.push({ name: 'projects' })">返回项目</el-button>
        <el-button v-if="task.isFinished" type="primary" @click="onViewResult">查看项目详情</el-button>
      </div>
    </div>

    <LogPanel :logs="task.logs" :show-hint="true" />
  </div>
</template>

