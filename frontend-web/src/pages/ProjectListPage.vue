<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { showApiError } from '@/api/http'
import { useChatStore } from '@/stores/chat'
import { useProjectStore } from '@/stores/project'
import { ArrowUpRight, GraduationCap, Plus, X } from 'lucide-vue-next'

const router = useRouter()
const projectStore = useProjectStore()
const chatStore = useChatStore()

type SessionSummary = {
  sessionId: string
  title: string
  status: '进行中' | '已完成'
  messageCount: number
  updatedAt: number
}

const creating = ref(false)
const promptText = ref('')
const helperVisible = ref(true)
const inputRef = ref<HTMLInputElement | null>(null)

const sessions = ref<SessionSummary[]>([])

const allProjects = computed(() => projectStore.projects)

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
  helperVisible.value = localStorage.getItem('__smartark_hide_helper__') !== '1'
})

const focusPrompt = async () => {
  await nextTick()
  inputRef.value?.focus()
}

const onQuickStart = async () => {
  const text = promptText.value.trim()
  if (!text) {
    ElMessage.warning('请先描述你的毕业题目')
    return
  }
  const title = text.length > 12 ? `${text.slice(0, 12)}...` : text
  creating.value = true
  try {
    const sessionId = await chatStore.startSession({ title, description: text, projectType: 'web' })
    // Do not wait for send, redirect immediately with query param
    await router.push({ name: 'chat', params: { sessionId }, query: { initialMessage: text } })
  } catch (e) {
    showApiError(e)
  } finally {
    creating.value = false
  }
}

const dismissHelper = () => {
  helperVisible.value = false
  localStorage.setItem('__smartark_hide_helper__', '1')
}

const openSession = async (sessionId: string) => {
  if (!sessionId) return
  await router.push({ name: 'chat', params: { sessionId } })
}
</script>

<template>
  <div class="grid grid-cols-12 gap-6">
    <div class="col-span-12 lg:col-span-4">
      <div class="flex items-center justify-between">
        <button
          type="button"
          class="flex w-full items-center justify-center gap-2 rounded-full bg-blue-600 px-4 py-2 text-sm font-semibold text-white shadow-sm hover:bg-blue-700"
          @click="focusPrompt"
        >
          <Plus class="h-4 w-4" />
          新建对话
        </button>
      </div>

      <div class="mt-4 space-y-3">
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

        <div v-if="sessions.length === 0" class="rounded-2xl border bg-white p-6 text-sm text-slate-500 dark:border-slate-900 dark:bg-slate-950 dark:text-slate-400">
          还没有对话，点击“新建对话”开始。
        </div>
      </div>
    </div>

    <div class="col-span-12 lg:col-span-8">
      <div class="flex justify-end">
        <div
          v-if="helperVisible"
          class="relative w-full max-w-lg rounded-2xl border bg-white p-4 shadow-sm ring-1 ring-blue-100 dark:border-slate-900 dark:bg-slate-950 dark:ring-blue-950/40"
        >
          <button
            type="button"
            class="absolute right-3 top-3 rounded-lg p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-700 dark:hover:bg-slate-900 dark:hover:text-slate-200"
            @click="dismissHelper"
            aria-label="关闭"
          >
            <X class="h-4 w-4" />
          </button>
          <div class="flex items-start gap-3">
            <div class="flex h-8 w-8 items-center justify-center rounded-xl bg-blue-50 text-blue-600 dark:bg-blue-950/40 dark:text-blue-300">
              <ArrowUpRight class="h-4 w-4" />
            </div>
            <div class="min-w-0">
              <div class="text-sm font-semibold">如何快速生成毕业项目?</div>
              <ol class="mt-2 list-decimal space-y-1 pl-5 text-xs text-slate-600 dark:text-slate-300">
                <li>在下方输入想要做的毕业题目</li>
                <li>AI 会自动为你生成完整项目</li>
                <li>输入后自动生成或复核你的题目</li>
              </ol>
            </div>
          </div>
        </div>
      </div>

      <div class="mt-10 flex flex-col items-center justify-center text-center">
        <div class="flex h-16 w-16 items-center justify-center rounded-2xl bg-blue-50 text-blue-600 shadow-sm dark:bg-blue-950/40 dark:text-blue-300">
          <GraduationCap class="h-7 w-7" />
        </div>
        <div class="mt-5 text-2xl font-semibold tracking-tight">描述你的毕业题目</div>
        <div class="mt-2 max-w-xl text-sm text-slate-500 dark:text-slate-400">
          输入题目或选择一个热门方向，AI 将为你生成完整的代码项目
        </div>

        <div class="mt-8 w-full max-w-2xl">
          <div class="flex items-center gap-3 rounded-full border bg-white px-5 py-4 shadow-md dark:border-slate-900 dark:bg-slate-950">
            <input
              ref="inputRef"
              v-model="promptText"
              class="h-10 w-full bg-transparent text-sm outline-none placeholder:text-slate-400 dark:placeholder:text-slate-600"
              placeholder="描述你的毕业题目，例如：基于 SpringBoot + Vue 的学生成绩管理系统..."
              @keydown.enter.prevent="onQuickStart"
            >
            <button
              type="button"
              class="flex h-10 w-10 items-center justify-center rounded-full bg-blue-600 text-white shadow-sm hover:bg-blue-700 disabled:cursor-not-allowed disabled:bg-slate-300 dark:disabled:bg-slate-800"
              :disabled="creating"
              @click="onQuickStart"
              aria-label="发送"
            >
              <ArrowUpRight class="h-5 w-5" />
            </button>
          </div>
        </div>

        <div class="mt-8 w-full max-w-2xl text-left">
          <div class="text-xs text-slate-400 dark:text-slate-500">我的项目</div>
          <div class="mt-3 grid grid-cols-1 gap-3 md:grid-cols-2 lg:grid-cols-3">
            <button
              v-for="p in allProjects"
              :key="p.id"
              type="button"
              class="rounded-2xl border bg-white p-4 text-left shadow-sm hover:shadow-md dark:border-slate-900 dark:bg-slate-950"
              @click="router.push({ name: 'project-detail', params: { projectId: p.id } })"
            >
              <div class="truncate text-sm font-semibold">{{ p.title }}</div>
              <div class="mt-2 truncate text-xs text-slate-500 dark:text-slate-400">{{ p.description || '—' }}</div>
            </button>
            <div
              v-if="allProjects.length === 0"
              class="col-span-full rounded-2xl border bg-white p-6 text-center text-sm text-slate-500 dark:border-slate-900 dark:bg-slate-950 dark:text-slate-400"
            >
              暂无项目
            </div>
          </div>
        </div>
      </div>

      <div class="fixed bottom-6 right-6 z-40 flex flex-col items-end gap-2">
        <button
          type="button"
          class="rounded-full bg-slate-900 px-4 py-2 text-xs font-semibold text-white shadow-lg hover:bg-slate-800"
          @click="ElMessage.info('请加入客户群获取支持')"
        >
          加入客户群获取支持
        </button>
        <button
          type="button"
          class="rounded-full bg-emerald-500 px-4 py-2 text-xs font-semibold text-white shadow-lg hover:bg-emerald-600"
          @click="ElMessage.info('已复制群二维码链接（示例）')"
        >
          加入客户群
        </button>
      </div>
    </div>
  </div>
</template>
