import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import ChatPage from '@/pages/ChatPage.vue'
import LoginPage from '@/pages/LoginPage.vue'
import ProjectListPage from '@/pages/ProjectListPage.vue'
import ProjectNewPage from '@/pages/ProjectNewPage.vue'
import ProjectDetailPage from '@/pages/ProjectDetailPage.vue'
import RegisterPage from '@/pages/RegisterPage.vue'
import StackConfirmPage from '@/pages/StackConfirmPage.vue'
import TaskProgressPage from '@/pages/TaskProgressPage.vue'
import TaskResultPage from '@/pages/TaskResultPage.vue'
import PreviewPage from '@/pages/PreviewPage.vue'

type RouteMeta = {
  requiresAuth?: boolean
  layout?: 'app' | 'blank'
}

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: '/projects',
  },
  {
    path: '/login',
    name: 'login',
    component: LoginPage,
    meta: { layout: 'blank' } satisfies RouteMeta,
  },
  {
    path: '/register',
    name: 'register',
    component: RegisterPage,
    meta: { layout: 'blank' } satisfies RouteMeta,
  },
  {
    path: '/projects',
    name: 'projects',
    component: ProjectListPage,
    meta: { requiresAuth: true } satisfies RouteMeta,
  },
  {
    path: '/projects/new',
    name: 'project-new',
    component: ProjectNewPage,
    meta: { requiresAuth: true } satisfies RouteMeta,
  },
  {
    path: '/chat/:sessionId',
    name: 'chat',
    component: ChatPage,
    meta: { requiresAuth: true } satisfies RouteMeta,
  },
  {
    path: '/projects/:projectId',
    name: 'project-detail',
    component: ProjectDetailPage,
    meta: { requiresAuth: true } satisfies RouteMeta,
  },
  {
    path: '/projects/:projectId/stack',
    name: 'stack-confirm',
    component: StackConfirmPage,
    meta: { requiresAuth: true } satisfies RouteMeta,
  },
  {
    path: '/tasks/:taskId/progress',
    name: 'task-progress',
    component: TaskProgressPage,
    meta: { requiresAuth: true } satisfies RouteMeta,
  },
  {
    path: '/tasks/:taskId/result',
    name: 'task-result',
    component: TaskResultPage,
    meta: { requiresAuth: true } satisfies RouteMeta,
  },
  {
    path: '/preview/:taskId',
    name: 'preview',
    component: PreviewPage,
    meta: { layout: 'blank' } satisfies RouteMeta,
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to) => {
  const meta = to.meta as RouteMeta
  if (!meta.requiresAuth) return true

  const auth = useAuthStore()
  if (auth.token) return true
  return { name: 'login', query: { redirect: to.fullPath } }
})

export default router
