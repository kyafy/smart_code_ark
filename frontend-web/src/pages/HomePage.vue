<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useProjectStore } from '@/stores/project'
import { GraduationCap, ArrowRight } from 'lucide-vue-next'

const router = useRouter()
const projectStore = useProjectStore()

type SessionSummary = {
  sessionId: string
  title: string
  status: '进行中' | '已完成'
  messageCount: number
  updatedAt: number
}

const sessions = ref<SessionSummary[]>([])
const allProjects = computed(() => projectStore.projects.slice(0, 3)) // 只展示最近3个项目

const loadLocalSessions = () => {
  try {
    const raw = localStorage.getItem('__smartark_mock_state__')
    if (!raw) {
      sessions.value = []
      return
    }
    const state = JSON.parse(raw) as any
    const list = Array.isArray(state.sessions) ? state.sessions : []
    sessions.value = list
      .map((s: any) => {
        const updatedAt = typeof s.updatedAt === 'number' ? s.updatedAt : Date.now()
        const messageCount = Array.isArray(s.messages) ? s.messages.length : 0
        return {
          sessionId: String(s.sessionId ?? ''),
          title: String(s.title ?? '未命名对话'),
          status: messageCount > 0 ? '进行中' : '进行中',
          messageCount,
          updatedAt,
        } satisfies SessionSummary
      })
      .filter((s: SessionSummary) => Boolean(s.sessionId))
      .sort((a, b) => b.updatedAt - a.updatedAt)
      .slice(0, 3) // 只展示最近3个会话
  } catch {
    sessions.value = []
  }
}

const formatAgo = (ts: number) => {
  const delta = Math.max(0, Date.now() - ts)
  const min = Math.floor(delta / 60000)
  if (min < 1) return '刚刚'
  if (min < 60) return `${min}分钟前`
  const hour = Math.floor(min / 60)
  if (hour < 24) return `${hour}小时前`
  const day = Math.floor(hour / 24)
  return `${day}天前`
}

onMounted(() => {
  void projectStore.refresh()
  loadLocalSessions()
})

const openSession = async (sessionId: string) => {
  if (!sessionId) return
  await router.push({ name: 'chat', params: { sessionId } })
}
</script>

<template>
  <div class="mx-auto max-w-5xl space-y-8">
    <!-- 欢迎区 -->
    <div class="flex flex-col items-center justify-center text-center py-12">
      <div class="flex h-16 w-16 items-center justify-center rounded-2xl bg-blue-50 text-blue-600 shadow-sm dark:bg-blue-950/40 dark:text-blue-300">
        <GraduationCap class="h-7 w-7" />
      </div>
      <div class="mt-5 text-2xl font-semibold tracking-tight">欢迎来到智码方舟</div>
      <div class="mt-2 max-w-xl text-sm text-slate-500 dark:text-slate-400">
        一站式 AI 驱动的代码项目生成与管理平台
      </div>
      <div class="mt-8">
        <button
          type="button"
          class="flex items-center gap-2 rounded-full bg-blue-600 px-6 py-3 text-sm font-semibold text-white shadow-sm hover:bg-blue-700"
          @click="router.push({ name: 'chat', params: { sessionId: 'new' } })"
        >
          去聊天创建新项目
          <ArrowRight class="h-4 w-4" />
        </button>
      </div>
    </div>

    <div class="grid grid-cols-1 md:grid-cols-2 gap-8">
      <!-- 最近会话 -->
      <div>
        <div class="flex items-center justify-between mb-4">
          <div class="text-base font-semibold">最近对话</div>
          <button
            type="button"
            class="text-sm text-blue-600 hover:text-blue-700 dark:text-blue-400"
            @click="router.push({ name: 'chat', params: { sessionId: 'new' } })"
          >
            全部对话
          </button>
        </div>
        <div class="space-y-3">
          <button
            v-for="s in sessions"
            :key="s.sessionId"
            type="button"
            class="w-full rounded-2xl border bg-white p-4 text-left shadow-sm transition hover:shadow-md dark:border-slate-900 dark:bg-slate-950"
            @click="openSession(s.sessionId)"
          >
            <div class="flex items-start justify-between gap-3">
              <div class="min-w-0">
                <div class="truncate text-sm font-semibold text-slate-800 dark:text-slate-100">{{ s.title }}</div>
              </div>
              <span class="shrink-0 rounded-full bg-blue-600 px-2 py-0.5 text-[11px] font-semibold text-white">{{ s.status }}</span>
            </div>
            <div class="mt-2 flex items-center justify-between text-xs text-slate-500 dark:text-slate-400">
              <div>{{ s.messageCount }}条消息</div>
              <div>{{ formatAgo(s.updatedAt) }}</div>
            </div>
          </button>
          <div v-if="sessions.length === 0" class="rounded-2xl border bg-white p-6 text-sm text-center text-slate-500 dark:border-slate-900 dark:bg-slate-950 dark:text-slate-400">
            暂无对话记录
          </div>
        </div>
      </div>

      <!-- 最近项目 -->
      <div>
        <div class="flex items-center justify-between mb-4">
          <div class="text-base font-semibold">最近项目</div>
          <button
            type="button"
            class="text-sm text-blue-600 hover:text-blue-700 dark:text-blue-400"
            @click="router.push({ name: 'projects' })"
          >
            全部项目
          </button>
        </div>
        <div class="space-y-3">
          <button
            v-for="p in allProjects"
            :key="p.id"
            type="button"
            class="w-full rounded-2xl border bg-white p-4 text-left shadow-sm hover:shadow-md dark:border-slate-900 dark:bg-slate-950"
            @click="router.push({ name: 'project-detail', params: { projectId: p.id } })"
          >
            <div class="truncate text-sm font-semibold">{{ p.title }}</div>
            <div class="mt-2 truncate text-xs text-slate-500 dark:text-slate-400">{{ p.description || '—' }}</div>
          </button>
          <div
            v-if="allProjects.length === 0"
            class="rounded-2xl border bg-white p-6 text-center text-sm text-slate-500 dark:border-slate-900 dark:bg-slate-950 dark:text-slate-400"
          >
            暂无项目
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
