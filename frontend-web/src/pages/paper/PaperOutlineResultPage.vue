<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { paperApi } from '@/api/endpoints'
import { showApiError } from '@/api/http'
import { usePaperStore } from '@/stores/paper'

const route = useRoute()
const router = useRouter()
const paper = usePaperStore()

const taskId = computed(() => String(route.params.taskId))
const loading = ref(false)

const chapters = computed<any[]>(() => (Array.isArray(paper.outline?.chapters) ? paper.outline?.chapters : []))
const references = computed<any[]>(() => (Array.isArray(paper.outline?.references) ? paper.outline?.references : []))
const qualityChecks = computed<Record<string, any>>(() => {
  if (paper.outline?.qualityChecks && typeof paper.outline.qualityChecks === 'object') {
    return paper.outline.qualityChecks as Record<string, any>
  }
  return {}
})

const load = async () => {
  if (!taskId.value) return
  loading.value = true
  try {
    const result = await paperApi.getOutline(taskId.value)
    paper.taskId = taskId.value
    paper.outline = result
  } catch (e) {
    showApiError(e)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
})

const onViewManuscript = async () => {
  await router.push({ name: 'paper-manuscript', params: { taskId: taskId.value } })
}

const onViewTraceability = async () => {
  await router.push({
    name: 'paper-manuscript',
    params: { taskId: taskId.value },
    query: { panel: 'evidence' },
  })
}
</script>

<template>
  <div class="flex flex-col gap-4">
    <div class="rounded-2xl border bg-white p-5">
      <div class="flex items-start justify-between gap-4">
        <div>
          <div class="text-base font-semibold">论文大纲结果</div>
          <div class="mt-1 text-sm text-slate-500">任务 ID：{{ taskId }}</div>
        </div>
        <div class="flex items-center gap-2">
          <el-button :loading="loading" @click="load">刷新结果</el-button>
          <el-button @click="router.push({ name: 'paper-outline-progress', params: { taskId } })">返回进度</el-button>
          <el-button plain @click="onViewTraceability">查看溯源证据</el-button>
          <el-button type="primary" plain @click="onViewManuscript">查看论文文稿</el-button>
        </div>
      </div>
    </div>

    <div class="grid grid-cols-1 gap-4 lg:grid-cols-3">
      <div class="rounded-2xl border bg-white p-5 lg:col-span-2">
        <div class="text-sm font-semibold">大纲章节</div>
        <div v-if="chapters.length === 0" class="mt-3 text-sm text-slate-500">暂无章节数据</div>
        <div v-else class="mt-3 flex flex-col gap-3">
          <div v-for="(c, idx) in chapters" :key="idx" class="rounded-xl border bg-slate-50 p-3">
            <div class="text-sm font-medium">{{ c.title || c.name || `第${idx + 1}章` }}</div>
            <div v-if="c.objective" class="mt-1 text-xs text-slate-600">{{ c.objective }}</div>
            <ul v-if="Array.isArray(c.sections) && c.sections.length" class="mt-2 list-disc space-y-1 pl-5 text-xs text-slate-600">
              <li v-for="(s, sidx) in c.sections" :key="sidx">
                {{ s.title || s.name || s }}
              </li>
            </ul>
          </div>
        </div>
      </div>

      <div class="rounded-2xl border bg-white p-5">
        <div class="text-sm font-semibold">研究问题</div>
        <ul v-if="paper.outline?.researchQuestions?.length" class="mt-3 list-disc space-y-1 pl-5 text-xs text-slate-600">
          <li v-for="(q, idx) in paper.outline?.researchQuestions" :key="idx">{{ q }}</li>
        </ul>
        <div v-else class="mt-3 text-xs text-slate-500">暂无研究问题</div>
      </div>
    </div>

    <div class="grid grid-cols-1 gap-4 lg:grid-cols-2">
      <div class="rounded-2xl border bg-white p-5">
        <div class="text-sm font-semibold">质量检查</div>
        <div v-if="Object.keys(qualityChecks).length === 0" class="mt-3 text-xs text-slate-500">暂无质检数据</div>
        <div v-else class="mt-3 space-y-2 text-xs">
          <div v-for="(value, key) in qualityChecks" :key="key" class="rounded-lg bg-slate-50 px-3 py-2">
            <span class="font-medium">{{ key }}：</span>{{ typeof value === 'object' ? JSON.stringify(value) : value }}
          </div>
        </div>
      </div>

      <div class="rounded-2xl border bg-white p-5">
        <div class="text-sm font-semibold">参考文献</div>
        <div v-if="references.length === 0" class="mt-3 text-xs text-slate-500">暂无参考文献</div>
        <ul v-else class="mt-3 list-decimal space-y-2 pl-5 text-xs text-slate-600">
          <li v-for="(r, idx) in references" :key="idx">
            {{ r.title || r.name || JSON.stringify(r) }}
          </li>
        </ul>
      </div>
    </div>
  </div>
</template>

