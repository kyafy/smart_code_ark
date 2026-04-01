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
    summary: 'Common enterprise scaffold for admin systems and workflow-heavy applications.',
    tag: 'Stable',
    stack: {
      backend: 'springboot',
      frontend: 'vue3',
      db: 'mysql',
    },
  },
  {
    key: 'fastapi-vue3-mysql',
    title: 'FastAPI + Vue3 + MySQL',
    summary: 'Python API + Vue frontend, good for rapid iteration and AI-centric products.',
    tag: 'Fast Delivery',
    stack: {
      backend: 'fastapi',
      frontend: 'vue3',
      db: 'mysql',
    },
  },
  {
    key: 'fastapi-nextjs-mysql',
    title: 'FastAPI + Next.js + MySQL',
    summary: 'API + modern React SSR frontend for SEO and rich web interactions.',
    tag: 'Web Product',
    stack: {
      backend: 'fastapi',
      frontend: 'nextjs',
      db: 'mysql',
    },
  },
  {
    key: 'django-vue3-mysql',
    title: 'Django + Vue3 + MySQL',
    summary: 'Batteries-included Python backend with clean Vue admin and business UI.',
    tag: 'Python Fullstack',
    stack: {
      backend: 'django',
      frontend: 'vue3',
      db: 'mysql',
    },
  },
  {
    key: 'nextjs-mysql',
    title: 'Next.js + MySQL',
    summary: 'Single framework fullstack route for SSR apps and BFF style delivery.',
    tag: 'All-in-one',
    stack: {
      backend: 'nextjs',
      frontend: 'nextjs',
      db: 'mysql',
    },
  },
  {
    key: 'uniapp-springboot-api',
    title: 'UniApp + Spring Boot API',
    summary: 'Mobile-first frontend with robust Java API layer for multi-end delivery.',
    tag: 'Mobile',
    stack: {
      backend: 'springboot',
      frontend: 'uniapp',
      db: 'mysql',
    },
  },
  {
    key: 'custom',
    title: 'Custom Stack',
    summary: 'Manually choose backend, frontend and database combinations.',
    tag: 'Flexible',
    stack: {
      backend: 'springboot',
      frontend: 'vue3',
      db: 'mysql',
    },
  },
]

const selectedPreset = ref<StackPresetKey>('springboot-vue3-mysql')
const form = ref<StackConfig>({ ...presets[0].stack })
const codegenEngine = ref<'llm' | 'jeecg_rule' | 'hybrid'>('llm')
const deliveryLevel = ref<'draft' | 'validated' | 'deliverable'>('draft')
const deployMode = ref<'none' | 'compose' | 'k8s'>('none')
const deployEnv = ref<'local' | 'test' | 'staging' | 'prod'>('test')
const autoBuildImage = ref(false)
const autoPushImage = ref(false)
const autoDeployTarget = ref(false)
const strictDelivery = ref(false)

const engineOptions = [
  { label: 'LLM Generation', value: 'llm' },
  { label: 'Jeecg Rule Render', value: 'jeecg_rule' },
  { label: 'Hybrid (Jeecg first, LLM fallback)', value: 'hybrid' },
]

const deliveryOptions = [
  { label: 'Draft', value: 'draft' },
  { label: 'Validated', value: 'validated' },
  { label: 'Deliverable', value: 'deliverable' },
]

const deployModeOptions = [
  { label: 'None', value: 'none' },
  { label: 'Docker Compose', value: 'compose' },
  { label: 'Kubernetes', value: 'k8s' },
]

const deployEnvOptions = [
  { label: 'Local', value: 'local' },
  { label: 'Test', value: 'test' },
  { label: 'Staging', value: 'staging' },
  { label: 'Production', value: 'prod' },
]

const releasePipelineEnabled = computed(() => {
  return (
    deployMode.value !== 'none' ||
    autoBuildImage.value ||
    autoPushImage.value ||
    autoDeployTarget.value
  )
})

const effectiveDeliveryLevel = computed(() => {
  if (releasePipelineEnabled.value) {
    return 'deliverable'
  }
  return deliveryLevel.value
})

const currentPreset = computed(
  () => presets.find((item) => item.key === selectedPreset.value) ?? presets[0],
)
const isCustomMode = computed(() => selectedPreset.value === 'custom')

const backendOptions = [
  { label: 'Spring Boot', value: 'springboot' },
  { label: 'FastAPI', value: 'fastapi' },
  { label: 'Next.js Fullstack', value: 'nextjs' },
  { label: 'NestJS', value: 'nestjs' },
  { label: 'Django', value: 'django' },
]

const frontendOptions = [
  { label: 'Vue3', value: 'vue3' },
  { label: 'Next.js', value: 'nextjs' },
  { label: 'React', value: 'react' },
  { label: 'UniApp', value: 'uniapp' },
]

const dbOptions = [
  { label: 'MySQL', value: 'mysql' },
  { label: 'PostgreSQL', value: 'postgres' },
]

watch(autoPushImage, (value) => {
  if (value) {
    autoBuildImage.value = true
  }
})

watch(autoDeployTarget, (value) => {
  if (value) {
    autoBuildImage.value = true
    if (deployEnv.value !== 'local') {
      autoPushImage.value = true
    }
  }
})

watch(
  () => [form.value.backend, form.value.frontend, form.value.db],
  ([backend, frontend, db]) => {
    const matchedPreset = presets.find(
      (item) =>
        item.key !== 'custom' &&
        item.stack.backend === backend &&
        item.stack.frontend === frontend &&
        item.stack.db === db,
    )
    selectedPreset.value = matchedPreset?.key ?? 'custom'
  },
)

function applyPreset(presetKey: StackPresetKey) {
  selectedPreset.value = presetKey
  const preset = presets.find((item) => item.key === presetKey)
  if (!preset) {
    return
  }
  form.value = { ...preset.stack }
}

const onConfirmAndGenerate = async () => {
  if (!sessionId.value) {
    ElMessage.warning('Missing sessionId, please restart from requirement confirmation')
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
      prd: prdData,
    })

    const templateId = selectedPreset.value === 'custom' ? null : selectedPreset.value
    const gen = await taskApi.generate({
      projectId: confirmed.projectId,
      options: {
        deliveryLevel: effectiveDeliveryLevel.value,
        templateId,
        codegenEngine: codegenEngine.value,
        deployMode: deployMode.value,
        deployEnv: deployEnv.value,
        strictDelivery: strictDelivery.value,
        enablePreview: true,
        enableAutoRepair: true,
        autoBuildImage: autoBuildImage.value,
        autoPushImage: autoPushImage.value,
        autoDeployTarget: autoDeployTarget.value,
      },
    })

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
          <div class="text-lg font-semibold text-slate-900">Stack Presets</div>
          <p class="mt-2 max-w-3xl text-sm leading-6 text-slate-500">
            Select a base stack and then configure code generation engine, release level, and deployment path.
            If Jeecg rule rendering is selected, backend will call Jeecg renderer first and then continue to preview and test flow.
          </p>
        </div>
        <div class="rounded-full bg-amber-50 px-4 py-2 text-xs font-semibold text-amber-700">
          Template-aware generation
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
            :class="preset.key === 'custom' ? 'bg-slate-100 text-slate-600' : 'bg-emerald-100 text-emerald-700'"
          >
            {{ preset.tag }}
          </span>
        </div>
        <p class="mt-3 text-sm leading-6 text-slate-500">{{ preset.summary }}</p>
        <div class="mt-4 flex flex-wrap gap-2 text-xs text-slate-600">
          <span class="rounded-full bg-slate-100 px-3 py-1">Backend: {{ preset.stack.backend }}</span>
          <span class="rounded-full bg-slate-100 px-3 py-1">Frontend: {{ preset.stack.frontend }}</span>
          <span class="rounded-full bg-slate-100 px-3 py-1">DB: {{ preset.stack.db }}</span>
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
          <div>Backend: {{ form.backend }}</div>
          <div>Frontend: {{ form.frontend }}</div>
          <div>DB: {{ form.db }}</div>
        </div>
      </div>

      <div v-if="isCustomMode" class="mt-6 grid grid-cols-1 gap-4 md:grid-cols-3">
        <div>
          <div class="mb-2 text-sm font-medium text-slate-700">Backend</div>
          <el-select v-model="form.backend" placeholder="Select backend" class="w-full">
            <el-option
              v-for="option in backendOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </div>
        <div>
          <div class="mb-2 text-sm font-medium text-slate-700">Frontend</div>
          <el-select v-model="form.frontend" placeholder="Select frontend" class="w-full">
            <el-option
              v-for="option in frontendOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </div>
        <div>
          <div class="mb-2 text-sm font-medium text-slate-700">Database</div>
          <el-select v-model="form.db" placeholder="Select database" class="w-full">
            <el-option v-for="option in dbOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </div>
      </div>

      <div
        v-else
        class="mt-6 rounded-3xl border border-dashed border-slate-200 bg-slate-50 px-5 py-4 text-sm leading-6 text-slate-600"
      >
        Preset mode is enabled. Choose "Custom Stack" above if you need to override backend/frontend/database.
      </div>

      <div class="mt-6 rounded-3xl border border-slate-200 bg-slate-50 p-5">
        <div class="text-sm font-semibold text-slate-900">Codegen and Deployment Options</div>
        <div class="mt-4 grid grid-cols-1 gap-4 md:grid-cols-2">
          <div>
            <div class="mb-2 text-sm font-medium text-slate-700">Codegen Engine</div>
            <el-select v-model="codegenEngine" class="w-full">
              <el-option v-for="option in engineOptions" :key="option.value" :label="option.label" :value="option.value" />
            </el-select>
            <p class="mt-2 text-xs text-slate-500">
              Jeecg Rule and Hybrid modes require template compatibility and Jeecg render service availability.
            </p>
          </div>

          <div>
            <div class="mb-2 text-sm font-medium text-slate-700">Delivery Level</div>
            <el-select v-model="deliveryLevel" class="w-full" :disabled="releasePipelineEnabled">
              <el-option
                v-for="option in deliveryOptions"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
            <p class="mt-2 text-xs text-slate-500">
              Effective level: <span class="font-semibold text-slate-700">{{ effectiveDeliveryLevel }}</span>
              <span v-if="releasePipelineEnabled"> (forced to deliverable by release pipeline)</span>
            </p>
          </div>

          <div>
            <div class="mb-2 text-sm font-medium text-slate-700">Deploy Mode</div>
            <el-select v-model="deployMode" class="w-full">
              <el-option
                v-for="option in deployModeOptions"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
          </div>

          <div>
            <div class="mb-2 text-sm font-medium text-slate-700">Deploy Environment</div>
            <el-select v-model="deployEnv" class="w-full" :disabled="deployMode === 'none'">
              <el-option
                v-for="option in deployEnvOptions"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
          </div>
        </div>

        <div class="mt-4 grid grid-cols-1 gap-3 md:grid-cols-2">
          <label class="flex items-center justify-between rounded-2xl border border-slate-200 bg-white px-4 py-3">
            <span class="text-sm text-slate-700">Auto Build Image</span>
            <el-switch v-model="autoBuildImage" />
          </label>
          <label class="flex items-center justify-between rounded-2xl border border-slate-200 bg-white px-4 py-3">
            <span class="text-sm text-slate-700">Auto Push Image</span>
            <el-switch v-model="autoPushImage" />
          </label>
          <label class="flex items-center justify-between rounded-2xl border border-slate-200 bg-white px-4 py-3">
            <span class="text-sm text-slate-700">Auto Deploy Target</span>
            <el-switch v-model="autoDeployTarget" />
          </label>
          <label class="flex items-center justify-between rounded-2xl border border-slate-200 bg-white px-4 py-3">
            <span class="text-sm text-slate-700">Strict Delivery (fail-fast)</span>
            <el-switch v-model="strictDelivery" />
          </label>
        </div>
      </div>

      <div class="mt-6 flex items-center gap-3">
        <el-button @click="router.back()">Back</el-button>
        <el-button type="primary" :loading="isSubmitting" @click="onConfirmAndGenerate">
          Confirm and Generate
        </el-button>
      </div>
    </section>
  </div>
</template>
