<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  CreditCard,
  HelpCircle,
  LayoutDashboard,
  LogOut,
  Moon,
  Settings,
  Share2,
  User,
} from 'lucide-vue-next'
import { useAuthStore } from '@/stores/auth'
import { useTheme } from '@/composables/useTheme'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const theme = useTheme()

const activeKey = computed(() => {
  const p = String(route.path)
  if (p.startsWith('/chat') || p.startsWith('/projects')) return 'chat'
  if (p.startsWith('/tasks')) return 'tasks'
  return p
})

const onLogout = () => {
  auth.logout()
  router.replace({ name: 'login' })
}

const toastSoon = () => {
  ElMessage.info('该功能正在开发中')
}
</script>

<template>
  <div class="min-h-dvh bg-slate-50 text-slate-900 dark:bg-slate-950 dark:text-slate-50">
    <div class="flex min-h-dvh">
      <aside class="w-[240px] shrink-0 border-r bg-white/70 backdrop-blur dark:border-slate-900 dark:bg-slate-950/60">
        <div class="flex items-center justify-between px-4 py-4">
          <div class="flex items-center gap-2">
            <div class="h-8 w-8 rounded-lg bg-gradient-to-br from-teal-400 to-blue-600" />
            <div class="text-sm font-semibold">智码方舟</div>
          </div>
          <button
            type="button"
            class="rounded-lg p-2 text-slate-500 hover:bg-slate-100 hover:text-slate-900 dark:text-slate-400 dark:hover:bg-slate-900 dark:hover:text-slate-50"
            @click="toastSoon"
            aria-label="设置"
          >
            <Settings class="h-4 w-4" />
          </button>
        </div>

        <div class="px-2">
          <div class="space-y-1">
            <button
              type="button"
              class="flex w-full items-center gap-3 rounded-xl px-3 py-2 text-sm"
              :class="activeKey === 'chat'
                ? 'bg-blue-50 text-blue-600 dark:bg-blue-950/40 dark:text-blue-300'
                : 'text-slate-700 hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-slate-900'"
              @click="router.push({ name: 'projects' })"
            >
              <span class="relative flex h-2 w-2 items-center justify-center">
                <span
                  v-if="activeKey === 'chat'"
                  class="absolute inline-flex h-2 w-2 rounded-full bg-blue-600 dark:bg-blue-300"
                />
              </span>
              <LayoutDashboard class="h-4 w-4 text-slate-500" />
              <span>我的项目</span>
            </button>

            <button
              type="button"
              class="flex w-full items-center gap-3 rounded-xl px-3 py-2 text-sm text-slate-700 hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-slate-900"
              @click="toastSoon"
            >
              <Share2 class="h-4 w-4 text-slate-500" />
              <span>分销中心</span>
            </button>

            <button
              type="button"
              class="flex w-full items-center gap-3 rounded-xl px-3 py-2 text-sm text-slate-700 hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-slate-900"
              @click="toastSoon"
            >
              <CreditCard class="h-4 w-4 text-slate-500" />
              <span>积分充值</span>
            </button>

            <button
              type="button"
              class="flex w-full items-center gap-3 rounded-xl px-3 py-2 text-sm text-slate-700 hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-slate-900"
              @click="toastSoon"
            >
              <HelpCircle class="h-4 w-4 text-slate-500" />
              <span>帮助</span>
            </button>
          </div>

          <div class="mt-10 space-y-1">
            <button
              type="button"
              class="flex w-full items-center gap-3 rounded-xl px-3 py-2 text-sm text-slate-700 hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-slate-900"
              @click="toastSoon"
            >
              <User class="h-4 w-4 text-slate-500" />
              <span>用户中心</span>
            </button>

            <button
              type="button"
              class="flex w-full items-center gap-3 rounded-xl px-3 py-2 text-sm text-slate-700 hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-slate-900"
              @click="theme.toggleTheme"
            >
              <Moon class="h-4 w-4 text-slate-500" />
              <span>深色模式</span>
            </button>

            <button
              v-if="auth.isAuthed"
              type="button"
              class="flex w-full items-center gap-3 rounded-xl px-3 py-2 text-sm text-slate-700 hover:bg-slate-100 dark:text-slate-200 dark:hover:bg-slate-900"
              @click="onLogout"
            >
              <LogOut class="h-4 w-4 text-slate-500" />
              <span>退出登录</span>
            </button>
          </div>
        </div>
      </aside>

      <main class="min-w-0 flex-1">
        <div class="px-6 py-6">
          <slot />
        </div>
      </main>
    </div>
  </div>
</template>
