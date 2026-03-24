<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { projectApi, taskApi } from '@/api/endpoints'
import { showApiError } from '@/api/http'
import type { StackConfig } from '@/types/api'
import { useChatStore } from '@/stores/chat'

type StackPresetKey =
  | 'springboot-vue3-mysql'
  | 'fastapi-vue3-mysql'
  | 'fastapi-nextjs-mysql'
  | 'django-vue3-mysql'
  | 'nextjs-mysql'
  | 'uniapp-springboot-api'
  | 'custom'

type StackPreset = {
  key: StackPresetKey
  title: string
  summary: string
  tag: string
  stack: StackConfig
}

const router = useRouter()
const route = useRoute()
const chatStore = useChatStore()

const sessionId = computed(() => String(route.query.sessionId ?? ''))
const isSubmitting = ref(false)

const presets: StackPreset[] = [
  {
    key: 'springboot-vue3-mysql',
    title: 'Spring Boot + Vue3 + MySQL',
    summary: '经典前后端分离方案，适合后台管理、业务系统和企业项目。',
    tag: '官方模板',
    stack: {
      backend: 'springboot',
      frontend: 'vue3',
      db: 'mysql'
    }
  },
  {
    key: 'fastapi-vue3-mysql',
    title: 'FastAPI + Vue3 + MySQL',
    summary: '轻量 Python API 搭配 Vue3，适合中后台、工具平台和快速原型。',
    tag: '官方模板',
    stack: {
      backend: 'fastapi',
      frontend: 'vue3',
      db: 'mysql'
    }
  },
  {
    key: 'fastapi-nextjs-mysql',
    title: 'FastAPI + Next.js + MySQL',
    summary: '适合需要 Python API 和 React Web 前端的管理台、SaaS 和内部平台。',
    tag: '官方模板',
    stack: {
      backend: 'fastapi',
      frontend: 'nextjs',
      db: 'mysql'
    }
  },
  {
    key: 'django-vue3-mysql',
    title: 'Django + Vue3 + MySQL',
    summary: '适合传统后台、内容系统和需要完整 Python Web 能力的业务项目。',
    tag: '官方模板',
    stack: {
      backend: 'django',
      frontend: 'vue3',
      db: 'mysql'
    }
  },
  {
    key: 'nextjs-mysql',
    title: 'Next.js + MySQL',
    summary: '基于 Next.js App Router 的全栈起点，适合 Web 产品和 SaaS 项目。',
    tag: '官方模板',
    stack: {
      backend: 'nextjs',
      frontend: 'nextjs',
      db: 'mysql'
    }
  },
  {
    key: 'uniapp-springboot-api',
    title: 'UniApp + Spring Boot API',
    summary: '适合小程序、H5 和 App 一体化项目，先做移动端再逐步扩展。',
    tag: '官方模板',
    stack: {
      backend: 'springboot',
      frontend: 'uniapp',
      db: 'mysql'
    }
  },
  {
    key: 'custom',
    title: '自定义技术栈',
    summary: '自由组合前后端与数据库，命中已知模板时仍会优先套用模板骨架。',
    tag: '高级模式',
    stack: {
      backend: 'springboot',
      frontend: 'vue3',
      db: 'mysql'
    }
  }
]

const selectedPreset = ref<StackPresetKey>('springboot-vue3-mysql')
const form = ref<StackConfig>({ ...presets[0].stack })

const currentPreset = computed(
  () => presets.find((item) => item.key === selectedPreset.value) ?? presets[0]
)
const isCustomMode = computed(() => selectedPreset.value === 'custom')

const backendOptions = [
  { label: 'Spring Boot', value: 'springboot' },
  { label: 'FastAPI', value: 'fastapi' },
  { label: 'Next.js Fullstack', value: 'nextjs' },
  { label: 'NestJS', value: 'nestjs' },
  { label: 'Django', value: 'django' }
]

const frontendOptions = [
  { label: 'Vue3', value: 'vue3' },
  { label: 'Next.js', value: 'nextjs' },
  { label: 'React', value: 'react' },
  { label: 'UniApp', value: 'uniapp' }
]

const dbOptions = [
  { label: 'MySQL', value: 'mysql' },
  { label: 'PostgreSQL', value: 'postgres' }
]

function applyPreset(presetKey: StackPresetKey) {
  selectedPreset.value = presetKey
  const preset = presets.find((item) => item.key === presetKey)
  if (!preset) {
    return
  }
  form.value = { ...preset.stack }
}

watch(
  () => [form.value.backend, form.value.frontend, form.value.db],
  ([backend, frontend, db]) => {
    const matchedPreset = presets.find(
      (item) =>
        item.key !== 'custom' &&
        item.stack.backend === backend &&
        item.stack.frontend === frontend &&
        item.stack.db === db
    )
    selectedPreset.value = matchedPreset?.key ?? 'custom'
  }
)

const onConfirmAndGenerate = async () => {
  if (!sessionId.value) {
    ElMessage.warning('缺少 sessionId，请返回对话页后重试')
    return
  }
  isSubmitting.value = true
  try {
    const prdData = chatStore.extractedRequirements
      ? JSON.stringify(chatStore.extractedRequirements)
      : ''
    const confirmed = await projectApi.confirm({
      sessionId: sessionId.value,
      stack: form.value,
      prd: prdData
    })
    const gen = await taskApi.generate({ projectId: confirmed.projectId })
    await router.replace({ name: 'task-progress', params: { taskId: gen.taskId } })
  } catch (error) {
    showApiError(error)
  } finally {
    isSubmitting.value = false
  }
}
</script>

<template>
  <div class="space-y-5">
    <section class="rounded-[28px] border border-slate-200 bg-white p-6 shadow-sm">
      <div class="flex flex-wrap items-start justify-between gap-4">
        <div>
          <div class="text-lg font-semibold text-slate-900">选择技术栈</div>
          <p class="mt-2 max-w-3xl text-sm leading-6 text-slate-500">
            已知官方组合会优先使用当前仓库内的 <code>template-repo</code>
            作为项目骨架，再补充业务代码。这样比纯模型空生成更稳，也更接近可直接运行的模板仓库。
          </p>
        </div>
        <div class="rounded-full bg-amber-50 px-4 py-2 text-xs font-semibold text-amber-700">
          官方模板优先
        </div>
      </div>
    </section>

    <section class="grid gap-4 lg:grid-cols-2">
      <button
        v-for="preset in presets"
        :key="preset.key"
        type="button"
        class="rounded-[28px] border p-5 text-left transition"
        :class="
          selectedPreset === preset.key
            ? 'border-sky-500 bg-sky-50 shadow-sm'
            : 'border-slate-200 bg-white hover:border-slate-300'
        "
        @click="applyPreset(preset.key)"
      >
        <div class="flex items-center justify-between gap-3">
          <div class="text-base font-semibold text-slate-900">{{ preset.title }}</div>
          <span
            class="rounded-full px-3 py-1 text-xs font-semibold"
            :class="
              preset.key === 'custom'
                ? 'bg-slate-100 text-slate-600'
                : 'bg-emerald-100 text-emerald-700'
            "
          >
            {{ preset.tag }}
          </span>
        </div>
        <p class="mt-3 text-sm leading-6 text-slate-500">{{ preset.summary }}</p>
        <div class="mt-4 flex flex-wrap gap-2 text-xs text-slate-600">
          <span class="rounded-full bg-slate-100 px-3 py-1">后端: {{ preset.stack.backend }}</span>
          <span class="rounded-full bg-slate-100 px-3 py-1">前端: {{ preset.stack.frontend }}</span>
          <span class="rounded-full bg-slate-100 px-3 py-1">数据库: {{ preset.stack.db }}</span>
        </div>
      </button>
    </section>

    <section class="rounded-[28px] border border-slate-200 bg-white p-6 shadow-sm">
      <div class="flex flex-wrap items-center justify-between gap-3">
        <div>
          <div class="text-base font-semibold text-slate-900">
            {{ currentPreset.title }}
          </div>
          <p class="mt-1 text-sm text-slate-500">
            {{ currentPreset.summary }}
          </p>
        </div>
        <div class="rounded-2xl bg-slate-50 px-4 py-3 text-sm text-slate-600">
          <div>后端: {{ form.backend }}</div>
          <div>前端: {{ form.frontend }}</div>
          <div>数据库: {{ form.db }}</div>
        </div>
      </div>

      <div v-if="isCustomMode" class="mt-6 grid grid-cols-1 gap-4 md:grid-cols-3">
        <div>
          <div class="mb-2 text-sm font-medium text-slate-700">后端</div>
          <el-select v-model="form.backend" placeholder="请选择" class="w-full">
            <el-option
              v-for="option in backendOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </div>
        <div>
          <div class="mb-2 text-sm font-medium text-slate-700">前端</div>
          <el-select v-model="form.frontend" placeholder="请选择" class="w-full">
            <el-option
              v-for="option in frontendOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </div>
        <div>
          <div class="mb-2 text-sm font-medium text-slate-700">数据库</div>
          <el-select v-model="form.db" placeholder="请选择" class="w-full">
            <el-option
              v-for="option in dbOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </div>
      </div>

      <div
        v-else
        class="mt-6 rounded-3xl border border-dashed border-slate-200 bg-slate-50 px-5 py-4 text-sm leading-6 text-slate-600"
      >
        当前选择会直接命中模板仓库。如果你想自由改栈，可以切到“自定义技术栈”。
      </div>

      <div class="mt-6 flex items-center gap-3">
        <el-button @click="router.back()">返回</el-button>
        <el-button type="primary" :loading="isSubmitting" @click="onConfirmAndGenerate">
          确认并开始生成
        </el-button>
      </div>
    </section>
  </div>
</template>
