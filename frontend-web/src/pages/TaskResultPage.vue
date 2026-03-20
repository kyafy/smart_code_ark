<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import ArtifactCards from '@/components/ArtifactCards.vue'
import { taskApi } from '@/api/endpoints'
import { showApiError } from '@/api/http'

const route = useRoute()
const router = useRouter()

const taskId = computed(() => String(route.params.taskId))
const previewStatus = ref('provisioning')
const previewUrl = ref('')
const previewExpireAt = ref('')
const previewLastError = ref('')
const provisioningPhase = ref('准备开始部署')
const isPreviewLoading = ref(false)
const isRebuildingPreview = ref(false)
const isDownloading = ref(false)
let pollTimer: number | null = null
let previewSse: EventSource | null = null
const sseMode = ref<'idle' | 'connecting' | 'connected' | 'fallback'>('idle')

const changeInstructions = ref('')
const isModifying = ref(false)

const isNoSandboxPreviewUrl = (url: string) => {
  try {
    const parsed = new URL(url, window.location.origin)
    return parsed.host === window.location.host && parsed.pathname.startsWith('/preview/')
  } catch {
    return url.startsWith('/preview/')
  }
}

const buildMockPreviewUrl = (id: string) => {
  const html = `
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>Mock Preview</title>
  <style>
    * { box-sizing: border-box; }
    body { margin: 0; font-family: Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; background: linear-gradient(135deg, #e0f2fe 0%, #eef2ff 100%); color: #0f172a; }
    .shell { min-height: 100vh; padding: 28px; display: flex; align-items: center; justify-content: center; }
    .card { width: min(920px, 100%); background: #ffffff; border-radius: 16px; box-shadow: 0 24px 48px rgba(15, 23, 42, 0.12); overflow: hidden; }
    .top { padding: 20px 24px; background: #0f172a; color: #f8fafc; display: flex; justify-content: space-between; align-items: center; }
    .title { font-size: 18px; font-weight: 700; }
    .badge { font-size: 12px; padding: 4px 10px; border-radius: 999px; background: #22c55e; color: #052e16; font-weight: 600; }
    .content { padding: 24px; display: grid; gap: 16px; }
    .panel { border: 1px solid #e2e8f0; border-radius: 12px; padding: 16px; }
    .panel h3 { margin: 0 0 8px; font-size: 14px; }
    .panel p { margin: 0; color: #475569; font-size: 13px; line-height: 1.5; }
    .grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
    .kpi { border-radius: 10px; border: 1px solid #e2e8f0; padding: 12px; background: #f8fafc; }
    .kpi .label { font-size: 12px; color: #64748b; }
    .kpi .val { margin-top: 6px; font-size: 16px; font-weight: 700; }
  </style>
</head>
<body>
  <div class="shell">
    <div class="card">
      <div class="top">
        <div class="title">Smart Code Ark 无沙箱预览</div>
        <div class="badge">MOCK</div>
      </div>
      <div class="content">
        <div class="panel">
          <h3>任务信息</h3>
          <p>Task ID: ${id}</p>
          <p>当前环境未接入沙箱运行时，已使用前端内置 mock 数据模拟预览页面。</p>
        </div>
        <div class="grid">
          <div class="kpi"><div class="label">模块数</div><div class="val">6</div></div>
          <div class="kpi"><div class="label">接口数</div><div class="val">24</div></div>
          <div class="kpi"><div class="label">页面数</div><div class="val">11</div></div>
        </div>
      </div>
    </div>
  </div>
</body>
</html>
  `.trim()
  return `data:text/html;charset=utf-8,${encodeURIComponent(html)}`
}

const effectivePreviewUrl = computed(() => {
  if (previewStatus.value !== 'ready') return ''
  const raw = previewUrl.value.trim()
  if (!raw) return ''
  if (isNoSandboxPreviewUrl(raw)) return buildMockPreviewUrl(taskId.value)
  return raw
})

const isUsingNoSandboxMock = computed(() => effectivePreviewUrl.value.startsWith('data:text/html'))
const canOpenPreviewInNewWindow = computed(() => Boolean(effectivePreviewUrl.value))

const stopPolling = () => {
  if (!pollTimer) return
  window.clearInterval(pollTimer)
  pollTimer = null
}

const stopPreviewSse = () => {
  if (!previewSse) return
  previewSse.close()
  previewSse = null
}

const startPreviewSse = () => {
  if (previewSse || !taskId.value || sseMode.value === 'fallback') return
  sseMode.value = 'connecting'
  provisioningPhase.value = '正在连接实时状态通道'
  try {
    previewSse = new EventSource(`/api/task/${taskId.value}/preview/events`)
    previewSse.onopen = () => {
      sseMode.value = 'connected'
      provisioningPhase.value = '实时状态通道已连接，等待部署结果'
      stopPolling()
    }
    previewSse.onmessage = (event) => {
      try {
        const payload = JSON.parse(event.data || '{}') as Record<string, string>
        previewStatus.value = payload.status || previewStatus.value
        previewUrl.value = payload.previewUrl || previewUrl.value
        previewExpireAt.value = payload.expireAt || previewExpireAt.value
        previewLastError.value = payload.lastError || previewLastError.value
        provisioningPhase.value = payload.phase || provisioningPhase.value
        if (previewStatus.value !== 'provisioning') {
          stopPreviewSse()
          stopPolling()
        }
      } catch {
        sseMode.value = 'fallback'
        provisioningPhase.value = '实时状态通道不可用，已回退轮询'
        stopPreviewSse()
        startPolling()
      }
    }
    previewSse.onerror = () => {
      sseMode.value = 'fallback'
      provisioningPhase.value = '实时状态通道不可用，已回退轮询'
      stopPreviewSse()
      startPolling()
    }
  } catch {
    sseMode.value = 'fallback'
    provisioningPhase.value = '实时状态通道不可用，已回退轮询'
    stopPreviewSse()
    startPolling()
  }
}

const startPolling = () => {
  if (pollTimer) return
  pollTimer = window.setInterval(() => {
    void loadPreview(true)
  }, 2500)
}

const loadPreview = async (silent = false) => {
  if (!taskId.value) return
  if (isPreviewLoading.value) return
  isPreviewLoading.value = true
  try {
    const p = await taskApi.preview(taskId.value)
    previewStatus.value = p.status || 'failed'
    previewUrl.value = p.previewUrl || ''
    previewExpireAt.value = p.expireAt || ''
    previewLastError.value = p.lastError || ''
    if (previewStatus.value === 'provisioning') {
      provisioningPhase.value = sseMode.value === 'connected' ? '预览部署进行中' : '正在部署预览实例'
      startPreviewSse()
      if (sseMode.value !== 'connected') {
        startPolling()
      }
    } else {
      stopPolling()
      stopPreviewSse()
      sseMode.value = 'idle'
    }
  } catch (e) {
    stopPolling()
    stopPreviewSse()
    sseMode.value = 'idle'
    if (!silent) showApiError(e)
    previewStatus.value = 'failed'
    previewLastError.value = '获取预览状态失败'
  } finally {
    isPreviewLoading.value = false
  }
}

onMounted(() => {
  void loadPreview()
})

onBeforeUnmount(() => {
  stopPolling()
  stopPreviewSse()
})

const onDownload = async () => {
  isDownloading.value = true
  try {
    const blob = await taskApi.download(taskId.value)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `smartark_${taskId.value}.zip`
    a.click()
    URL.revokeObjectURL(url)
  } catch (e) {
    showApiError(e)
  } finally {
    isDownloading.value = false
  }
}

const onOpenPreview = () => {
  if (!effectivePreviewUrl.value) return
  window.open(effectivePreviewUrl.value, '_blank', 'noopener,noreferrer')
}

const onRebuildPreview = async () => {
  if (isRebuildingPreview.value) return
  isRebuildingPreview.value = true
  try {
    const p = await taskApi.rebuildPreview(taskId.value)
    previewStatus.value = p.status || 'provisioning'
    previewUrl.value = p.previewUrl || ''
    previewExpireAt.value = p.expireAt || ''
    previewLastError.value = p.lastError || ''
    if (previewStatus.value === 'provisioning') {
      provisioningPhase.value = '预览重建已触发，等待部署结果'
      sseMode.value = 'idle'
      startPolling()
    }
    ElMessage.success('已开始重建预览')
  } catch (e) {
    showApiError(e)
  } finally {
    isRebuildingPreview.value = false
  }
}

const onModify = async () => {
  const text = changeInstructions.value.trim()
  if (!text) {
    ElMessage.warning('请输入修改指令')
    return
  }
  isModifying.value = true
  try {
    const res = await taskApi.modify(taskId.value, { changeInstructions: text })
    await router.push({ name: 'task-progress', params: { taskId: res.taskId } })
  } catch (e) {
    showApiError(e)
  } finally {
    isModifying.value = false
  }
}
</script>

<template>
  <div class="flex flex-col gap-4">
    <div class="rounded-2xl border bg-white p-5">
      <div class="flex items-start justify-between gap-4">
        <div>
          <div class="text-base font-semibold">交付结果</div>
          <div class="mt-1 text-sm text-slate-500">任务 ID：{{ taskId }}</div>
        </div>
        <div class="flex items-center gap-2">
          <el-button @click="router.push({ name: 'task-progress', params: { taskId } })">返回进度</el-button>
          <el-button :loading="isPreviewLoading" @click="loadPreview">刷新预览状态</el-button>
          <el-button type="primary" :loading="isDownloading" @click="onDownload">下载 ZIP</el-button>
        </div>
      </div>
    </div>

    <div class="grid grid-cols-1 gap-4 lg:grid-cols-3">
      <div class="lg:col-span-2">
        <ArtifactCards :preview-url="previewStatus === 'ready' && !isUsingNoSandboxMock ? previewUrl : ''" />
        <div class="mt-4 rounded-2xl border bg-white p-4">
          <div class="flex items-center justify-between gap-2">
            <div class="text-sm font-semibold">在线预览</div>
            <el-tag v-if="previewStatus === 'ready'" type="success" effect="plain">ready</el-tag>
            <el-tag v-else-if="previewStatus === 'provisioning'" type="warning" effect="plain">provisioning</el-tag>
            <el-tag v-else-if="previewStatus === 'expired'" type="info" effect="plain">expired</el-tag>
            <el-tag v-else type="danger" effect="plain">{{ previewStatus || 'failed' }}</el-tag>
          </div>

          <div v-if="previewStatus === 'provisioning'" class="mt-3 rounded-xl bg-amber-50 p-3 text-sm text-amber-700">
            <div>预览部署中：{{ provisioningPhase }}</div>
            <div v-if="sseMode === 'fallback'" class="mt-1 text-xs">实时通道不可用，当前使用轮询模式（2.5 秒一次）。</div>
            <div v-else class="mt-1 text-xs">优先使用实时通道，失败后自动回退到轮询模式。</div>
          </div>

          <div v-else-if="previewStatus === 'ready' && effectivePreviewUrl" class="mt-3">
            <div v-if="isUsingNoSandboxMock" class="mb-3 rounded-xl bg-sky-50 p-3 text-xs text-sky-700">
              当前环境未接入沙箱运行时，已启用前端内置 mock 预览数据。
            </div>
            <div class="h-[520px] overflow-hidden rounded-xl border">
              <iframe :src="effectivePreviewUrl" class="h-full w-full border-0" title="Task Preview" />
            </div>
            <div class="mt-3 flex items-center justify-between gap-2">
              <div class="text-xs text-slate-500">过期时间：{{ previewExpireAt || '—' }}</div>
              <el-button size="small" type="primary" :disabled="!canOpenPreviewInNewWindow" @click="onOpenPreview">
                新窗口打开
              </el-button>
            </div>
          </div>

          <div v-else-if="previewStatus === 'failed'" class="mt-3 rounded-xl bg-rose-50 p-3 text-sm text-rose-700">
            <div>预览部署失败：{{ previewLastError || '请稍后重试或先下载 ZIP 在本地运行。' }}</div>
            <el-button class="mt-3" size="small" type="danger" plain :loading="isRebuildingPreview" @click="onRebuildPreview">
              重建预览
            </el-button>
          </div>

          <div v-else-if="previewStatus === 'expired'" class="mt-3 rounded-xl bg-slate-100 p-3 text-sm text-slate-600">
            <div>预览已过期，请重建预览；下载 ZIP 与修改生成仍可继续使用。</div>
            <el-button class="mt-3" size="small" type="primary" plain :loading="isRebuildingPreview" @click="onRebuildPreview">
              重建预览
            </el-button>
          </div>

          <div v-else class="mt-3 rounded-xl bg-slate-100 p-3 text-sm text-slate-600">
            当前暂无可展示的预览信息，请稍后刷新。
          </div>
        </div>
      </div>

      <div class="lg:col-span-1">
        <div class="rounded-2xl border bg-white p-5">
          <div class="text-sm font-semibold">继续修改</div>
          <div class="mt-1 text-xs text-slate-500">用中文描述改动，例如：订单模块增加待发货状态</div>

          <el-input
            v-model="changeInstructions"
            class="mt-4"
            type="textarea"
            :autosize="{ minRows: 4, maxRows: 8 }"
            placeholder="请输入修改指令"
          />
          <el-button class="mt-3 w-full" type="primary" :loading="isModifying" @click="onModify">
            生成修改版
          </el-button>

          <div class="mt-4 rounded-xl bg-slate-50 p-3 text-xs text-slate-600">
            建议一次只提出一个明确修改点，便于模型准确应用。
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

