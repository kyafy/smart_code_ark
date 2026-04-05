<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useProjectStore } from '@/stores/project'
import { ElMessage, ElMessageBox } from 'element-plus'
import { chatApi } from '@/api/endpoints'
import { GraduationCap, ArrowRight, Trash2 } from 'lucide-vue-next'

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
const topicInput = ref('')
const submitting = ref(false)
const allProjects = computed(() => projectStore.projects.slice(0, 3)) // 只展示最近3个项目

const loadLocalSessions = async () => {
  if (import.meta.env.VITE_USE_MOCK !== 'true') {
    try {
      const list = await chatApi.getSessions()
      sessions.value = list
        .map((s: any) => {
          const updatedAt = typeof s.updatedAt === 'number'
            ? s.updatedAt
            : (Date.parse(String(s.updatedAt ?? '')) || Date.now())
          const messageCount = typeof s.messageCount === 'number' ? s.messageCount : (Array.isArray(s.messages) ? s.messages.length : 0)

          return {
            sessionId: String(s.sessionId ?? ''),
            title: String(s.title || '未命名对话'),
            status: messageCount > 0 ? '进行中' : '进行中',
            messageCount,
            updatedAt,
          } satisfies SessionSummary
        })
        .filter((s: SessionSummary) => Boolean(s.sessionId))
        .sort((a, b) => b.updatedAt - a.updatedAt)
        .slice(0, 3) // 只展示最近3个会话
    } catch (e) {
      console.error(e)
    }
    return
  }

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
  void loadLocalSessions()
})

const openSession = async (sessionId: string) => {
  if (!sessionId) return
  await router.push({ name: 'chat', params: { sessionId } })
}

const deleteSession = async (sessionId: string) => {
  if (!sessionId) return
  try {
    await ElMessageBox.confirm('删除后将无法在首页最近对话中查看该会话。', '确认删除会话', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
    if (import.meta.env.VITE_USE_MOCK === 'false') {
      await chatApi.deleteSession(sessionId)
    } else {
      const raw = localStorage.getItem('__smartark_mock_state__')
      if (raw) {
        const state = JSON.parse(raw) as any
        const list = Array.isArray(state.sessions) ? state.sessions : []
        state.sessions = list.filter((s: any) => String(s.sessionId ?? '') !== sessionId)
        localStorage.setItem('__smartark_mock_state__', JSON.stringify(state))
      }
    }
    void loadLocalSessions()
    ElMessage.success('会话已删除')
  } catch (err) {
    if (String((err as any)?.message || '').includes('cancel')) return
    ElMessage.error('删除失败，请稍后重试')
  }
}

const startFromHome = async () => {
  const topic = topicInput.value.trim()
  if (!topic) return
  submitting.value = true
  try {
    await router.push({
      name: 'chat',
      params: { sessionId: 'new' },
      query: { initialMessage: topic }
    })
    topicInput.value = ''
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="mx-auto max-w-6xl space-y-8">
    <!-- 欢迎区 -->
    <div class="flex flex-col items-center justify-center text-center py-10">
      <div class="flex h-16 w-16 items-center justify-center rounded-2xl bg-blue-50 text-blue-600 shadow-sm dark:bg-blue-950/40 dark:text-blue-300">
        <GraduationCap class="h-7 w-7" />
      </div>
      <div class="mt-5 text-3xl font-semibold tracking-tight">欢迎来到智研方舟</div>
      <div class="mt-2 max-w-2xl text-sm text-slate-500 dark:text-slate-400">
        输入你的毕设题目，直接进入对话并自动开始与大模型交互
      </div>
      <div class="mt-8 w-full max-w-3xl">
        <div class="flex flex-col gap-3 md:flex-row">
          <input
            v-model="topicInput"
            class="h-12 w-full rounded-2xl border border-slate-200 bg-slate-50 px-4 text-sm outline-none transition focus:border-blue-400 focus:bg-white dark:border-slate-800 dark:bg-slate-900 dark:focus:border-blue-600"
            placeholder="例如：基于 Spring Boot + Vue3 的实验室设备预约管理系统"
            @keydown.enter.prevent="startFromHome"
          >
          <button
            type="button"
            class="inline-flex h-12 items-center justify-center gap-2 whitespace-nowrap rounded-2xl bg-blue-600 px-6 text-sm font-semibold text-white shadow-sm transition hover:bg-blue-700 disabled:cursor-not-allowed disabled:bg-blue-600 disabled:opacity-70"
            :disabled="submitting || !topicInput.trim()"
            @click="startFromHome"
          >
            提交
            <ArrowRight class="h-4 w-4" />
          </button>
        </div>
        <div class="mt-3 text-xs text-slate-500 dark:text-slate-400">
          将自动创建新会话并发送题目，无需再点击“新建会话”
        </div>
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
          <div
            v-for="s in sessions"
            :key="s.sessionId"
            class="group rounded-2xl border bg-white p-4 shadow-sm transition hover:shadow-md dark:border-slate-900 dark:bg-slate-950"
          >
            <div class="flex items-start justify-between gap-3">
              <button type="button" class="min-w-0 flex-1 text-left" @click="openSession(s.sessionId)">
                <div class="truncate text-sm font-semibold text-slate-800 dark:text-slate-100">{{ s.title }}</div>
              </button>
              <div class="flex items-center gap-2">
                <span class="shrink-0 rounded-full bg-blue-600 px-2 py-0.5 text-[11px] font-semibold text-white">{{ s.status }}</span>
                <button
                  type="button"
                  class="inline-flex h-7 w-7 items-center justify-center rounded-md text-slate-400 transition hover:bg-slate-100 hover:text-rose-500 dark:hover:bg-slate-900 opacity-0 group-hover:opacity-100"
                  @click="deleteSession(s.sessionId)"
                >
                  <Trash2 class="h-4 w-4" />
                </button>
              </div>
            </div>
            <div class="mt-2 flex items-center justify-between text-xs text-slate-500 dark:text-slate-400">
              <div>{{ s.messageCount }}条消息</div>
              <div>{{ formatAgo(s.updatedAt) }}</div>
            </div>
          </div>
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
