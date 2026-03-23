<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { paperApi, projectApi } from '@/api/endpoints'
import { ApiRequestError } from '@/api/http'
import { useProjectStore } from '@/stores/project'
import type { PaperProjectSummary } from '@/types/api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { LayoutDashboard, Trash2 } from 'lucide-vue-next'

const router = useRouter()
const projectStore = useProjectStore()

const allProjects = computed(() => projectStore.projects)
const paperProjects = ref<PaperProjectSummary[]>([])
const activeTab = ref<'project' | 'paper'>('project')
const deletingProjectId = ref<string>('')

const statusLabel = (status: string) => {
  if (status === 'finished') return '已完成'
  if (status === 'running') return '生成中'
  if (status === 'queued') return '排队中'
  if (status === 'failed') return '失败'
  return status || '未知'
}

const openPaper = async (item: PaperProjectSummary) => {
  if (item.status === 'finished') {
    await router.push({ name: 'paper-outline-result', params: { taskId: item.taskId } })
    return
  }
  await router.push({ name: 'paper-outline-progress', params: { taskId: item.taskId } })
}

const confirmDeleteProject = async () => {
  await ElMessageBox.confirm('删除后该项目将从列表中移除。', '确认删除项目', {
    confirmButtonText: '删除',
    cancelButtonText: '取消',
    type: 'warning',
  })
}

const onDeleteProject = async (projectId: string) => {
  if (!projectId) return
  if (deletingProjectId.value) return
  try {
    await confirmDeleteProject()
    deletingProjectId.value = projectId
    await projectApi.delete(projectId)
    await projectStore.refresh()
    ElMessage.success('项目已删除')
  } catch (err) {
    if (String((err as any)?.message || '').includes('cancel')) return
    if (err instanceof ApiRequestError) {
      if (err.code === 1003 || err.httpStatus === 403) {
        ElMessage.error('无权限删除该项目')
        return
      }
      if (err.code === 1004 || err.httpStatus === 404) {
        ElMessage.warning('项目不存在或已删除')
        await projectStore.refresh()
        return
      }
    }
    ElMessage.error('删除失败，请稍后重试')
  } finally {
    if (deletingProjectId.value === projectId) {
      deletingProjectId.value = ''
    }
  }
}

onMounted(() => {
  void projectStore.refresh()
  void (async () => {
    try {
      paperProjects.value = await paperApi.list()
    } catch {
      paperProjects.value = []
    }
  })()
})
</script>

<template>
  <div class="mx-auto max-w-5xl">
    <div class="flex items-center gap-3 mb-6">
      <div class="flex h-10 w-10 items-center justify-center rounded-xl bg-blue-50 text-blue-600 dark:bg-blue-950/40 dark:text-blue-300">
        <LayoutDashboard class="h-5 w-5" />
      </div>
      <div class="text-xl font-semibold">我的项目</div>
    </div>

    <div class="mb-5 inline-flex rounded-xl border bg-white p-1 shadow-sm dark:border-slate-900 dark:bg-slate-950">
      <button
        type="button"
        class="rounded-lg px-4 py-2 text-sm font-medium transition"
        :class="activeTab === 'project'
          ? 'bg-blue-600 text-white'
          : 'text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-900'"
        @click="activeTab = 'project'"
      >
        项目
      </button>
      <button
        type="button"
        class="rounded-lg px-4 py-2 text-sm font-medium transition"
        :class="activeTab === 'paper'
          ? 'bg-blue-600 text-white'
          : 'text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-900'"
        @click="activeTab = 'paper'"
      >
        论文
      </button>
    </div>

    <div v-if="activeTab === 'project'" class="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3">
      <div
        v-for="p in allProjects"
        :key="p.id"
        class="relative rounded-2xl border bg-white p-5 text-left shadow-sm hover:shadow-md dark:border-slate-900 dark:bg-slate-950"
      >
        <button
          type="button"
          class="block w-full text-left"
          @click="router.push({ name: 'project-detail', params: { projectId: p.id } })"
        >
          <div class="truncate text-base font-semibold">{{ p.title }}</div>
          <div class="mt-2 truncate text-sm text-slate-500 dark:text-slate-400">{{ p.description || '—' }}</div>
        </button>

        <button
          type="button"
          class="absolute right-3 top-3 inline-flex h-9 w-9 items-center justify-center rounded-xl border bg-white text-slate-600 shadow-sm hover:bg-slate-50 dark:border-slate-900 dark:bg-slate-950 dark:text-slate-300 dark:hover:bg-slate-900"
          :disabled="deletingProjectId === p.id"
          @click.stop="onDeleteProject(p.id)"
        >
          <Trash2 class="h-4 w-4" />
        </button>
      </div>
      <div
        v-if="allProjects.length === 0"
        class="col-span-full rounded-2xl border bg-white p-10 text-center text-sm text-slate-500 dark:border-slate-900 dark:bg-slate-950 dark:text-slate-400"
      >
        暂无项目
      </div>
    </div>

    <div v-else class="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3">
      <button
        v-for="p in paperProjects"
        :key="p.taskId"
        type="button"
        class="rounded-2xl border bg-white p-5 text-left shadow-sm hover:shadow-md dark:border-slate-900 dark:bg-slate-950"
        @click="openPaper(p)"
      >
        <div class="line-clamp-2 text-base font-semibold">{{ p.topic }}</div>
        <div class="mt-2 text-sm text-slate-500 dark:text-slate-400">{{ p.discipline }} · {{ p.degreeLevel }}</div>
        <div class="mt-3 inline-flex rounded-full bg-blue-600 px-2 py-0.5 text-xs font-semibold text-white">
          {{ statusLabel(p.status) }}
        </div>
      </button>
      <div
        v-if="paperProjects.length === 0"
        class="col-span-full rounded-2xl border bg-white p-10 text-center text-sm text-slate-500 dark:border-slate-900 dark:bg-slate-950 dark:text-slate-400"
      >
        暂无论文
      </div>
    </div>
  </div>
</template>
