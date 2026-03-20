<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'
import { paperApi } from '@/api/endpoints'
import { showApiError } from '@/api/http'
import { usePaperStore } from '@/stores/paper'
import { buildPaperManuscriptMarkdown } from '@/mappers/paperToManuscript'

const route = useRoute()
const router = useRouter()
const paper = usePaperStore()

const taskId = computed(() => String(route.params.taskId))
const loading = ref(false)

const manuscriptMd = computed(() => {
  if (!paper.outline) return ''
  return buildPaperManuscriptMarkdown(paper.outline)
})

const load = async () => {
  if (!taskId.value) return
  if (paper.outline?.taskId === taskId.value) return
  loading.value = true
  try {
    const [outline, manuscript] = await Promise.all([
      paperApi.getOutline(taskId.value),
      paperApi.getManuscript(taskId.value),
    ])
    paper.taskId = taskId.value
    paper.outline = {
      ...outline,
      topic: manuscript.topic || outline.topic,
      topicRefined: manuscript.topicRefined || outline.topicRefined,
      manuscript: manuscript.manuscript,
      qualityScore: manuscript.qualityScore ?? outline.qualityScore,
      rewriteRound: manuscript.rewriteRound ?? outline.rewriteRound,
    }
  } catch (e) {
    showApiError(e)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
})

const copyText = async (text: string) => {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.left = '-9999px'
    ta.style.top = '0'
    document.body.appendChild(ta)
    ta.focus()
    ta.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    return ok
  } catch {
    return false
  }
}

const onCopy = async () => {
  const ok = await copyText(manuscriptMd.value)
  if (ok) {
    ElMessage.success('已复制文稿（Markdown）')
  } else {
    ElMessage.error('复制失败，请手动选择复制')
  }
}
</script>

<template>
  <div class="flex flex-col gap-4">
    <div class="rounded-2xl border bg-white p-5">
      <div class="flex items-start justify-between gap-4">
        <div>
          <div class="text-base font-semibold">论文文稿预览</div>
          <div class="mt-1 text-sm text-slate-500">任务 ID：{{ taskId }}</div>
        </div>
        <div class="flex items-center gap-2">
          <el-button :loading="loading" @click="load">刷新</el-button>
          <el-button @click="router.push({ name: 'paper-outline-result', params: { taskId } })">查看大纲</el-button>
          <el-button type="primary" :disabled="!manuscriptMd" @click="onCopy">复制文稿</el-button>
        </div>
      </div>
    </div>

    <div class="rounded-2xl border bg-white p-6">
      <div v-if="loading" class="space-y-3">
        <div class="h-4 w-1/3 animate-pulse rounded bg-slate-100" />
        <div class="h-3 w-2/3 animate-pulse rounded bg-slate-100" />
        <div class="h-3 w-full animate-pulse rounded bg-slate-100" />
        <div class="h-3 w-5/6 animate-pulse rounded bg-slate-100" />
      </div>
      <div v-else-if="!manuscriptMd" class="text-sm text-slate-500">暂无文稿内容，请先生成论文框架结果。</div>
      <MarkdownRenderer v-else :content="manuscriptMd" />
    </div>
  </div>
</template>
