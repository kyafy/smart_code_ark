<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import ChatComposer from '@/components/ChatComposer.vue'
import ChatMessageList from '@/components/ChatMessageList.vue'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'
import { showApiError } from '@/api/http'
import { useChatStore } from '@/stores/chat'
import { ArrowRight, CheckCircle2, Plus, Sparkles } from 'lucide-vue-next'

const router = useRouter()
const route = useRoute()
const chat = useChatStore()

const sessionId = computed(() => String(route.params.sessionId))
const isReady = ref(false)
const activeStep = ref<1 | 2 | 3>(2)

type SessionSummary = {
  sessionId: string
  title: string
  status: '进行中' | '编审中' | '已完成'
  messageCount: number
  updatedAt: number
}

const sessions = ref<SessionSummary[]>([])

const loadLocalSessions = async () => {
  if (import.meta.env.VITE_USE_MOCK === 'false') {
    try {
      const { chatApi } = await import('@/api/endpoints')
      const list = await chatApi.getSessions()
      sessions.value = list
        .map((s: any) => {
          const updatedAt = typeof s.updatedAt === 'number' ? s.updatedAt : Date.now()
          const messageCount = typeof s.messageCount === 'number' ? s.messageCount : 0
          return {
            sessionId: String(s.sessionId ?? ''),
            title: String(s.title ?? '未命名对话'),
            status: messageCount > 0 ? '编审中' : '进行中',
            messageCount,
            updatedAt,
          } satisfies SessionSummary
        })
        .filter((s: SessionSummary) => Boolean(s.sessionId))
    } catch (e) {
      console.error('Failed to load history sessions', e)
      sessions.value = []
    }
  } else {
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
            status: messageCount > 0 ? '编审中' : '进行中',
            messageCount,
            updatedAt,
          } satisfies SessionSummary
        })
        .filter((s: SessionSummary) => Boolean(s.sessionId))
    } catch {
      sessions.value = []
    }
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

const lastAssistant = computed(() => {
  const reversed = [...chat.messages].sort((a, b) => b.createdAt - a.createdAt)
  return reversed.find((m) => m.speaker === 'assistant') ?? null
})

const messageCount = computed(() => chat.messages.length)
const headerTitle = computed(() => chat.title || sessions.value.find((s) => s.sessionId === sessionId.value)?.title || '未命名对话')
const headerStatus = computed(() => (chat.hasDraft ? '编审中' : '进行中'))

const overviewText = computed(() => {
  if (chat.extractedRequirements?.description) {
    return chat.extractedRequirements.description
  }
  if (lastAssistant.value?.message) return lastAssistant.value.message
  return `本项目旨在围绕一个可快速交付的毕业设计应用，通过 AI 辅助完成需求梳理、数据库设计与工程生成。`
})

onMounted(async () => {
  if (!sessionId.value) return
  loadLocalSessions()
  if (import.meta.env.VITE_USE_MOCK === 'false') {
    await chat.loadSession(sessionId.value)
  } else {
    chat.hydrateFromMock(sessionId.value)
  }
  chat.sessionId = sessionId.value
  isReady.value = true

  // Check for initial message from query params
  const initialMessage = route.query.initialMessage as string
  if (initialMessage && chat.messages.length === 0) {
    // Remove query param to prevent resending on refresh
    router.replace({ query: {} })
    // Send initial message
    await onSend(initialMessage)
  }
})

watch(sessionId, async (newSid) => {
  if (newSid && newSid !== chat.sessionId) {
    if (import.meta.env.VITE_USE_MOCK === 'false') {
      await chat.loadSession(newSid)
    } else {
      chat.hydrateFromMock(newSid)
    }
    activeStep.value = 2
  }
})

const onSend = async (text: string) => {
  try {
    if (activeStep.value === 1) {
      const sid = await chat.startSession({
        title: form.value.title || text.slice(0, 10),
        projectType: form.value.projectType,
        description: text,
      })
      await router.replace({ query: { session: sid } })
      activeStep.value = 2
    }
    await chat.send(text)
    await loadLocalSessions()
  } catch (err) {
    showApiError(err)
  }
}

const onConfirm = async () => {
  if (!chat.sessionId) {
    ElMessage.warning('会话未初始化')
    return
  }
  await router.push({ name: 'stack-confirm', params: { projectId: 'draft' }, query: { sessionId: chat.sessionId } })
}

const onNewChat = async () => {
  await router.push({ name: 'projects' })
}

const onOpenSession = async (sid: string) => {
  if (!sid) return
  chat.hydrateFromMock(sid)
  await router.push({ name: 'chat', params: { sessionId: sid } })
}
</script>

<template>
  <div class="grid grid-cols-12 gap-6">
    <div class="col-span-12 lg:col-span-3">
      <button
        type="button"
        class="flex w-full items-center justify-center gap-2 rounded-full bg-blue-600 px-4 py-2 text-sm font-semibold text-white shadow-sm hover:bg-blue-700"
        @click="onNewChat"
      >
        <Plus class="h-4 w-4" />
        新建对话
      </button>

      <div class="mt-4 space-y-3">
        <button
          v-for="s in sessions"
          :key="s.sessionId"
          type="button"
          class="w-full rounded-2xl border p-4 text-left shadow-sm transition dark:border-slate-900"
          :class="s.sessionId === sessionId
            ? 'bg-blue-600 text-white hover:bg-blue-700'
            : 'bg-white hover:shadow-md dark:bg-slate-950'"
          @click="onOpenSession(s.sessionId)"
        >
          <div class="flex items-start justify-between gap-3">
            <div class="min-w-0">
              <div class="truncate text-sm font-semibold">{{ s.title }}</div>
            </div>
            <span
              class="shrink-0 rounded-full px-2 py-0.5 text-[11px] font-semibold"
              :class="s.sessionId === sessionId ? 'bg-white/15 text-white' : 'bg-blue-600 text-white'"
            >
              {{ s.status }}
            </span>
          </div>
          <div class="mt-2 flex items-center justify-between text-xs" :class="s.sessionId === sessionId ? 'text-white/80' : 'text-slate-500 dark:text-slate-400'">
            <div>{{ s.messageCount }}条消息</div>
            <div>{{ formatAgo(s.updatedAt) }}</div>
          </div>
        </button>

        <div v-if="sessions.length === 0" class="rounded-2xl border bg-white p-6 text-sm text-slate-500 dark:border-slate-900 dark:bg-slate-950 dark:text-slate-400">
          暂无对话记录
        </div>
      </div>
    </div>

    <div class="col-span-12 lg:col-span-6">
      <div class="rounded-2xl border bg-white p-5 shadow-sm dark:border-slate-900 dark:bg-slate-950">
        <div class="flex items-start justify-between gap-4">
          <div class="min-w-0">
            <div class="truncate text-base font-semibold">{{ headerTitle }}</div>
            <div class="mt-1 flex items-center gap-2 text-xs text-slate-500 dark:text-slate-400">
              <span>{{ headerStatus }}</span>
              <span>·</span>
              <span>{{ messageCount }}条消息</span>
            </div>
          </div>
          <div class="flex items-center gap-2">
            <el-button size="small" @click="router.push({ name: 'projects' })">关闭</el-button>
            <el-button size="small" type="primary" :disabled="!chat.hasDraft" @click="onConfirm">生成项目</el-button>
          </div>
        </div>

        <div class="mt-4 flex flex-wrap items-center gap-2">
          <button
            type="button"
            class="flex items-center gap-2 rounded-full px-3 py-1 text-xs font-semibold"
            :class="activeStep === 1
              ? 'bg-emerald-50 text-emerald-700 dark:bg-emerald-950/40 dark:text-emerald-300'
              : 'bg-slate-100 text-slate-600 hover:bg-slate-200 dark:bg-slate-900 dark:text-slate-300 dark:hover:bg-slate-800'"
            @click="activeStep = 1"
          >
            <CheckCircle2 class="h-4 w-4" />
            推荐需求
          </button>
          <button
            type="button"
            class="flex items-center gap-2 rounded-full px-3 py-1 text-xs font-semibold"
            :class="activeStep === 2
              ? 'bg-blue-50 text-blue-700 dark:bg-blue-950/40 dark:text-blue-300'
              : 'bg-slate-100 text-slate-600 hover:bg-slate-200 dark:bg-slate-900 dark:text-slate-300 dark:hover:bg-slate-800'"
            @click="activeStep = 2"
          >
            <Sparkles class="h-4 w-4" />
            AI确认需求
          </button>
          <button
            type="button"
            class="flex items-center gap-2 rounded-full px-3 py-1 text-xs font-semibold"
            :class="activeStep === 3
              ? 'bg-blue-50 text-blue-700 dark:bg-blue-950/40 dark:text-blue-300'
              : 'bg-slate-100 text-slate-600 hover:bg-slate-200 dark:bg-slate-900 dark:text-slate-300 dark:hover:bg-slate-800'"
            @click="activeStep = 3"
          >
            <ArrowRight class="h-4 w-4" />
            生成项目
          </button>
        </div>

        <div class="mt-4 rounded-2xl border bg-white p-5 shadow-sm dark:border-slate-900 dark:bg-slate-950">
          <div v-if="activeStep === 1" class="text-sm leading-relaxed">
            <div class="text-sm font-semibold">推荐需求</div>
            <div class="mt-2 text-sm text-slate-700 dark:text-slate-200">
              请补充角色、权限、核心业务流程与数据字段。完成后进入“AI确认需求”。
            </div>
            <div class="mt-3 flex flex-wrap gap-2">
              <span v-for="m in (chat.draftModules.length ? chat.draftModules : ['用户','商品','订单'])" :key="m" class="rounded-full bg-slate-100 px-3 py-1 text-xs text-slate-700 dark:bg-slate-900 dark:text-slate-200">
                {{ m }}
              </span>
            </div>
          </div>

          <div v-else-if="activeStep === 2" class="text-sm leading-relaxed">
            <div class="text-sm font-semibold mb-3">AI确认需求</div>
            <ChatMessageList :messages="chat.messages" />
          </div>

          <div v-else class="text-sm leading-relaxed">
            <div class="text-sm font-semibold">生成项目</div>
            <div class="mt-2 text-sm text-slate-700 dark:text-slate-200">
              确认需求后，点击右上角“生成项目”进入技术栈选择并开始生成。
            </div>
          </div>
        </div>
      </div>

      <div class="mt-4">
        <ChatComposer :disabled="!isReady || chat.isSending" @send="onSend" />
      </div>
    </div>

    <div class="col-span-12 lg:col-span-3">
      <div class="rounded-2xl border bg-white p-5 shadow-sm dark:border-slate-900 dark:bg-slate-950">
        <div class="flex items-start justify-between gap-3">
          <div>
            <div class="text-sm font-semibold">需求文档</div>
            <div class="mt-1">
              <span class="inline-flex rounded-full bg-emerald-50 px-2 py-0.5 text-[11px] font-semibold text-emerald-700 dark:bg-emerald-950/40 dark:text-emerald-300">
                自动生成
              </span>
            </div>
          </div>
          <el-button size="small" type="success" @click="onConfirm" :disabled="!chat.hasDraft">启动生成</el-button>
        </div>

        <div class="mt-4 space-y-4">
          <div class="rounded-2xl border bg-slate-50 p-4 dark:border-slate-900 dark:bg-slate-900/40">
            <div class="text-xs font-semibold text-slate-700 dark:text-slate-200">项目概览</div>
            <div class="mt-2">
              <MarkdownRenderer :content="overviewText" />
            </div>
          </div>

          <div v-if="chat.extractedRequirements?.features?.length" class="rounded-2xl border bg-slate-50 p-4 dark:border-slate-900 dark:bg-slate-900/40">
            <div class="text-xs font-semibold text-slate-700 dark:text-slate-200">核心功能</div>
            <div class="mt-3 flex flex-col gap-2">
              <div
                v-for="f in chat.extractedRequirements.features"
                :key="f"
                class="rounded-xl border bg-white px-3 py-2 text-xs text-slate-700 shadow-sm dark:border-slate-900 dark:bg-slate-950 dark:text-slate-200"
              >
                {{ f }}
              </div>
            </div>
          </div>

          <div v-if="chat.extractedRequirements?.pages?.length" class="rounded-2xl border bg-slate-50 p-4 dark:border-slate-900 dark:bg-slate-900/40">
            <div class="text-xs font-semibold text-slate-700 dark:text-slate-200">页面结构</div>
            <div class="mt-3 flex flex-wrap gap-2">
              <span v-for="p in chat.extractedRequirements.pages" :key="p" class="rounded-full bg-white px-3 py-1 text-xs text-slate-700 shadow-sm dark:bg-slate-950 dark:text-slate-200">{{ p }}</span>
            </div>
          </div>

          <div v-if="!chat.extractedRequirements" class="rounded-2xl border bg-slate-50 p-4 dark:border-slate-900 dark:bg-slate-900/40">
            <div class="text-xs font-semibold text-slate-700 dark:text-slate-200">功能模块</div>
            <div class="mt-3 flex flex-col gap-2">
              <div
                v-for="m in (chat.draftModules.length ? chat.draftModules : ['学生端','教师端','用户管理','数据可视化'])"
                :key="m"
                class="rounded-xl border bg-white px-3 py-2 text-xs text-slate-700 shadow-sm dark:border-slate-900 dark:bg-slate-950 dark:text-slate-200"
              >
                {{ m }}
              </div>
            </div>
          </div>
        </div>

        <div class="mt-4 flex flex-col items-end gap-2">
          <button
            type="button"
            class="w-full rounded-full bg-slate-900 px-4 py-2 text-xs font-semibold text-white shadow-sm hover:bg-slate-800"
            @click="ElMessage.info('请加入客户群获取支持')"
          >
            加入客户群获取支持
          </button>
          <button
            type="button"
            class="w-full rounded-full bg-emerald-500 px-4 py-2 text-xs font-semibold text-white shadow-sm hover:bg-emerald-600 disabled:cursor-not-allowed disabled:bg-slate-300 dark:disabled:bg-slate-800"
            :disabled="!chat.hasDraft"
            @click="onConfirm"
          >
            立即生成
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
